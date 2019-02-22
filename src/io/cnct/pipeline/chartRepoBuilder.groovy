// /src/io/cnct/pipeline/chartRepoBuilder.groovy
package io.cnct.pipeline;

def executePipeline(pipelineDef) {
  // script globals initialized in initializeHandler
  isChartChange = false
  isMasterBuild = false
  isSelfCommit = false
  isPRBuild = false
  isSelfTest = false
  pipeline = pipelineDef
  pipelineEnvVariables = []
  pullSecrets = []
  defaults = parseYaml(libraryResource("io/cnct/pipeline/defaults.yaml"))
  slackError = ""

  properties(
    [
      disableConcurrentBuilds()
    ]
  )

  def err = null
  def notifyMessage = ""

  try {
    initializeHandler();

    if (isPRBuild || isSelfTest) {
      runPR()
    }

    if (isSelfTest) {
      initializeHandler();
    }

    if (isMasterBuild || isSelfTest) {
      runMerge()
    }

    notifyMessage = 'Build succeeded for ' + "${env.JOB_NAME} number ${env.BUILD_NUMBER} (${env.RUN_DISPLAY_URL})"
  } catch (e) {
    if (!isSelfCommit) {
      currentBuild.result = 'FAILURE'
      notifyMessage = 'Build failed for ' + 
        "${env.JOB_NAME} number ${env.BUILD_NUMBER} (${env.RUN_DISPLAY_URL}) : ${e.getMessage()}"
      err = e
    }
  } finally {
    
    if (isSelfCommit) {
      currentBuild.result = 'SUCCESS'
      return
    }

    postCleanup(err)
    
    if (err) {
      slackFail(pipeline, notifyMessage)

      def sw = new StringWriter()
      def pw = new PrintWriter(sw)
      err.printStackTrace(pw)
      echo sw.toString()
      
      throw err
    } else {
      slackOk(pipeline, notifyMessage)
    } 
  }
}

// try to cleanup any hanging helm release or namespaces resulting from premature termination
// through either job ABORT or error
def postCleanup(err) {
  withTools(
    defaults: defaults,
    envVars: pipelineEnvVariables,
    containers: getScriptImages(),
    imagePullSecrets: pullSecrets) {
    inside(label: buildId('tools')) {
      container('helm') {
        stage('Cleaning up') {

          // always cleanup workspace pvc
          // always cleanup var lib docker pvc
          // always clean up pull secrets
          def deleteSteps = [:]
          for (pull in pipeline.pullSecrets ) {
            def deleteSecrets = "kubectl delete secret ${pull.name}-${kubeName(env.JOB_NAME)} --namespace=${defaults.jenkinsNamespace} || true"
            deleteSteps["${pull.name}-${kubeName(env.JOB_NAME)}"] = { sh(deleteSecrets) }
          }
          deleteSteps["jenkins-workspace-${kubeName(env.JOB_NAME)}"] = { 
            sh("kubectl delete pvc jenkins-workspace-${kubeName(env.JOB_NAME)} --namespace ${defaults.jenkinsNamespace} || true") 
          }
          deleteSteps["jenkins-varlibdocker-${kubeName(env.JOB_NAME)}"] = { 
            sh("kubectl delete pvc jenkins-varlibdocker-${kubeName(env.JOB_NAME)} --namespace ${defaults.jenkinsNamespace} || true") 
          }
          parallel deleteSteps

          if (isPRBuild || isSelfTest) {

            // unstash kubeconfig files
            unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

            // clean up failed releases if present
            withEnv(
            [
              "KUBECONFIG=${env.BUILD_ID}-test.kubeconfig"
            ]) {
              sh("helm list --namespace ${kubeName(env.JOB_NAME)} --short --failed --tiller-namespace ${pipeline.helm.namespace} | while read line; do helm delete \$line --purge --tiller-namespace ${pipeline.helm.namespace}; done")
              sh("helm list --namespace ${pipeline.stage.namespace} --short --failed --tiller-namespace ${pipeline.helm.namespace} | while read line; do helm delete \$line --purge --tiller-namespace ${pipeline.helm.namespace}; done")
            }

            // clean up klar jobs
            def parallelCveSteps = [:]
            for (container in pipeline.builds) {
              if (container.script || container.commands) {
                continue
              }

              def cveJobName = kubeName(helmReleaseName("klar-${env.JOB_NAME}-${container.image}"))
              parallelCveSteps[cveJobName] = { sh("kubectl delete job ${cveJobName} --namespace ${defaults.jenkinsNamespace} || true") }
            }
            parallel parallelCveSteps

            // additional cleanup on error that destroy handler might have missed
            if (err) {
              def helmCleanSteps = [:] 

              for (chart in pipeline.deployments) {
                if (chart.chart) {
                  def commandString = "helm delete ${helmReleaseName(chart.release + "-" + kubeName(env.JOB_NAME))} --purge --tiller-namespace ${pipeline.helm.namespace} || true"
                  helmCleanSteps["${chart.chart}-deploy-test"] = { 
                    withEnv(
                      [
                        "KUBECONFIG=${env.BUILD_ID}-test.kubeconfig"
                      ]) {
                      sh(commandString) 
                    }
                  }
                }
              }

              echo("Contents of ${kubeName(env.JOB_NAME)} namespace:")
              sh("kubectl describe all --namespace ${kubeName(env.JOB_NAME)} --kubeconfig=${env.BUILD_ID}-test.kubeconfig || true")

              parallel helmCleanSteps

              sh("kubectl delete namespace ${kubeName(env.JOB_NAME)} --kubeconfig=${env.BUILD_ID}-test.kubeconfig || true")
            }
          }
        }
      }
    }
  }
}

def initializeHandler() {
  def scmVars
  
  // init all the conditionals we care about
  // This is a PR build if CHANGE_ID is set by git SCM
  isPRBuild = (env.CHANGE_ID) ? true : false
  // This is a master build if this is not a PR build
  isMasterBuild = !isPRBuild
  // TODO: this would be initialized from a job parameter.
  isSelfTest = false


  // collect the env values to be injected
  pipelineEnvVariables += containerEnvVar(key: 'DOCKER_HOST', value: 'localhost:2375')
  pipelineEnvVariables += containerEnvVar(key: 'VAULT_ADDR', value: pipeline.vault.server)

  withTools(
    envVars: pipelineEnvVariables, 
    defaults: defaults) {
    inside(label: buildId('tools')) {      
      scmVars = checkout scm

      container('yaml') {
        stage('Make sure this is not a self-version bump') {
          if (ciSkip(defaults)) {
            isSelfCommit = true
            error('Skipping self-commit') 
          }
        }

        stage('process required kubernetes primitives') {
          echo('Processing pipeline worskspace pvc templates')
          def processPVCSteps = [:]
          processPVCSteps["jenkins-workspace-${kubeName(env.JOB_NAME)}"] = {
            writeFile(file: "${pwd()}/jenkins-workspace-${kubeName(env.JOB_NAME)}.yaml", 
              text: libraryResource("io/cnct/pipeline/utility-pvc.yaml"))
            replaceInYaml("${pwd()}/jenkins-workspace-${kubeName(env.JOB_NAME)}.yaml", 
              'metadata.name', "jenkins-workspace-${kubeName(env.JOB_NAME)}")
            replaceInYaml("${pwd()}/jenkins-workspace-${kubeName(env.JOB_NAME)}.yaml", 
              'spec.resources.requests.storage', defaults.workspaceSize)
            replaceInYaml("${pwd()}/jenkins-workspace-${kubeName(env.JOB_NAME)}.yaml", 
              'spec.storageClassName', defaults.storageClass)
          }

          processPVCSteps["jenkins-varlibdocker-${kubeName(env.JOB_NAME)}"] = {
            writeFile(file: "${pwd()}/jenkins-varlibdocker-${kubeName(env.JOB_NAME)}.yaml", 
              text: libraryResource("io/cnct/pipeline/utility-pvc.yaml"))
            replaceInYaml("${pwd()}/jenkins-varlibdocker-${kubeName(env.JOB_NAME)}.yaml", 
              'metadata.name', "jenkins-varlibdocker-${kubeName(env.JOB_NAME)}")
            replaceInYaml("${pwd()}/jenkins-varlibdocker-${kubeName(env.JOB_NAME)}.yaml", 
              'spec.resources.requests.storage', defaults.dockerBuilderSize)
            replaceInYaml("${pwd()}/jenkins-varlibdocker-${kubeName(env.JOB_NAME)}.yaml", 
              'spec.storageClassName', defaults.storageClass)
          } 

          parallel  processPVCSteps        
        }        
      }

      container('helm') {
        stage('Create job pvcs') {
          def createPVCSteps = [:]
          createPVCSteps["jenkins-workspace-${kubeName(env.JOB_NAME)}"] = { 
            sh("cat ${pwd()}/jenkins-workspace-${kubeName(env.JOB_NAME)}.yaml")
            sh("kubectl apply -f ${pwd()}/jenkins-workspace-${kubeName(env.JOB_NAME)}.yaml --namespace ${defaults.jenkinsNamespace}")
          }

          createPVCSteps["jenkins-varlibdocker-${kubeName(env.JOB_NAME)}"] = { 
            sh("cat ${pwd()}/jenkins-varlibdocker-${kubeName(env.JOB_NAME)}.yaml")
            sh("kubectl apply -f ${pwd()}/jenkins-varlibdocker-${kubeName(env.JOB_NAME)}.yaml --namespace ${defaults.jenkinsNamespace}")
          }

          parallel createPVCSteps
        }
        

        stage('Create image pull secrets') {
          def createPullSteps = [:]
          for (pull in pipeline.pullSecrets ) {
            withCredentials([string(credentialsId: defaults.vault.credentials, variable: 'VAULT_TOKEN')]) {
              def vaultToken = env.VAULT_TOKEN
              def secretVal = getVaultKV(
                defaults,
                vaultToken,
                pull.password)

              def createSecrets = """
                set +x
                kubectl apply secret docker-registry ${pull.name}-${kubeName(env.JOB_NAME)} \
                  --docker-server=${pull.server} \
                  --docker-username=${pull.username} \
                  --docker-password='${secretVal}' \
                  --docker-email='${pull.email}' --namespace=${defaults.jenkinsNamespace}
                set -x"""

              pullSecrets += "${pull.name}-${kubeName(env.JOB_NAME)}"
              createPullSteps["${pull.name}-${kubeName(env.JOB_NAME)}"] = { sh(createSecrets) }
            }
          }

          parallel createPullSteps
        }

        stage('Get target cluster configuration') {
          withCredentials([string(credentialsId: defaults.vault.credentials, variable: 'VAULT_TOKEN')]) {
            def vaultToken = env.VAULT_TOKEN

            if (isPRBuild || isSelfTest) {
              def testKubeconfig = getVaultKV(
                defaults,
                vaultToken,
                defaults.targets.testCluster)
              writeFile(file: "${env.BUILD_ID}-test.kubeconfig", text: testKubeconfig)
              def stagingKubeconfig = getVaultKV(
                defaults,
                vaultToken,
                defaults.targets.stagingCluster)
              writeFile(file: "${env.BUILD_ID}-staging.kubeconfig", text: stagingKubeconfig)
            }

            if (isMasterBuild || isSelfTest) {
              def prodKubeconfig = getVaultKV(
                defaults,
                vaultToken,
                defaults.targets.prodCluster)
              writeFile(file: "${env.BUILD_ID}-prod.kubeconfig", text: prodKubeconfig)
            }
          }

          stash(
            name: "${env.BUILD_ID}-kube-configs".replaceAll('-','_'),
            includes: "**/*.kubeconfig"
          )
        }
      }

      container('vault') {
        stage('Set global environment variables') {          
          for (envValue in pipeline.envValues ) {
            if (envValue.secret) {
              withCredentials([string(credentialsId: defaults.vault.credentials, variable: 'VAULT_TOKEN')]) {
                def vaultToken = env.VAULT_TOKEN
                def secretVal = getVaultKV(
                  defaults,
                  vaultToken,
                  envValue.secret)
                pipelineEnvVariables += envVar(
                  key: envValue.envVar, 
                  value: secretVal)
              }
            } else if (envValue.env) {
              
              def valueFromEnv = ""
              if (env[envValue.env]) {
                valueFromEnv = env[envValue.env]
              } else {
                valueFromEnv = scmVars[envValue.env]
              }

              pipelineEnvVariables += envVar(
                  key: envValue.envVar, 
                  value: valueFromEnv)  
            } else {
              pipelineEnvVariables += envVar(
                key: envValue.envVar, 
                value: envValue.value)
            }
          }
        }
      }
    }
  } 
}

def runPR() {
  def scmVars
  withTools(
    defaults: defaults,
    envVars: pipelineEnvVariables,
    containers: getScriptImages(),
    imagePullSecrets: pullSecrets,
    workspaceClaimName: "jenkins-workspace-${kubeName(env.JOB_NAME)}",
    dockerClaimName: "jenkins-varlibdocker-${kubeName(env.JOB_NAME)}") {       
    
    inside(label: buildId('tools')) {
      stage('Checkout') {
        scmVars = checkout scm
      }
    

      // run before scripts
      executeUserScript('Executing global \'before\' scripts', pipeline.beforeScript)

      // update the versionfile
      if (!isPathChange(defaults.versionfile, "${env.CHANGE_ID}")) {
        bumpVersionfile(defaults)
      }

      buildsTestHandler(scmVars)
      chartLintHandler(scmVars)

      deployToTestHandler(scmVars)
      helmTestHandler(scmVars)
      testTestHandler(scmVars)
      destroyHandler(scmVars)
      
      buildsStageHandler(scmVars)
      deployToStageHandler(scmVars)
      stageTestHandler(scmVars)

      // run after scripts
      executeUserScript('Executing global \'after\' scripts', pipeline.afterScript)
    }
  }
}

def runMerge() {
  def scmVars
  withTools(
    defaults: defaults,
    envVars: pipelineEnvVariables,
    containers: getScriptImages(),
    imagePullSecrets: pullSecrets,
    workspaceClaimName: "jenkins-workspace-${kubeName(env.JOB_NAME)}",
    dockerClaimName: "jenkins-varlibdocker-${kubeName(env.JOB_NAME)}") {
    inside(label: buildId('tools')) {
      stage('Checkout') {
        scmVars = checkout scm
      }

      // run before scripts
      executeUserScript('Executing global \'before\' scripts', pipeline.beforeScript)

      // update the versionfile
      if (!isPathChange(defaults.versionfile, "${env.CHANGE_ID}")) {
        bumpVersionfile(defaults)
      }

      buildsProdHandler(scmVars)
      chartProdVersion(scmVars)
      deployToProdHandler(scmVars)
      startTriggers(scmVars)
      pushGitChanges(scmVars)

      // run after scripts
      executeUserScript('Executing global \'after\' scripts', pipeline.afterScript)
    }
  }
}

// Build changed builds folders 
// Tag with commit sha
// Tag with a test tag
// then push to repo
def buildsTestHandler(scmVars) {
  def gitCommit = scmVars.GIT_COMMIT
  def chartsWithContainers = []

  def parallelBinaryBuildSteps = [:]
  def binaryBuildCounter = 0
  def parallelContainerBuildSteps = [:]
  def parallelTagSteps = [:]
  def parallelPushSteps = [:]
  def parallelCveSteps = [:]

  executeUserScript('Executing test \'before\' script', pipeline.test.beforeScript)

  // get tag text
  def useTag = makeDockerTag(defaults, gitCommit)

  container('vault') {
    stage('Collect build targets') {
      for (container in pipeline.builds) {
        if (container.script || container.commands) {
          def description = "Executing binary build ${binaryBuildCounter} using ${container.image}"
          def _container = container
          parallelBinaryBuildSteps["binary-build-${binaryBuildCounter}"] = { 
            executeUserScript(description, _container) 
          }
          binaryBuildCounter += 1 
        } else {
          // build steps
          def buildCommandString = "docker build -t\
            ${defaults.docker.registry}/${container.image}:${useTag} --pull " 
          if (container.buildArgs) {
            def argMap = [:]

            for (buildArg in container.buildArgs) {
              if (buildArg.secret) {
                withCredentials([string(credentialsId: defaults.vault.credentials, variable: 'VAULT_TOKEN')]) {
                  def vaultToken = env.VAULT_TOKEN
                  def secretVal = getVaultKV(
                    defaults,
                    vaultToken,
                    buildArg.secret)
                  argMap += ["${buildArg.arg}" : "${secretVal.trim()}"]
                }
              } else if (buildArg.env) {
                
                def valueFromEnv = ""
                if (env[buildArg.env]) {
                  valueFromEnv = env[buildArg.env]
                } else {
                  valueFromEnv = scmVars[buildArg.env]
                }

                argMap += ["${buildArg.arg}" : "${valueFromEnv.trim()}"]  
              } else {
                argMap += ["${buildArg.arg}" : "${buildArg.value.trim()}"]
              }
            }

            buildCommandString += mapToParams('--build-arg', argMap)
          }
          buildCommandString += " ${container.dockerContext} --file ${dockerfileLocation(defaults, container.locationOverride, container.context)}"

          parallelContainerBuildSteps["${container.image.replaceAll('/','_')}-build"] = { sh(buildCommandString) }


          // tag steps
          def tagCommandString = "docker tag ${defaults.docker.registry}/${container.image}:${useTag}\
           ${defaults.docker.registry}/${container.image}:${defaults.docker.testTag}"
          parallelTagSteps["${container.image.replaceAll('/','_')}-tag"] = { sh(tagCommandString) }

          // push steps
          def pushShaCommandString = "docker push ${defaults.docker.registry}/${container.image}:${useTag}"
          def pushTagCommandString = "docker push ${defaults.docker.registry}/${container.image}:${defaults.docker.testTag}"
          
          parallelPushSteps["${container.image.replaceAll('/','_')}-push-sha"] = { sh(pushShaCommandString) }
          parallelPushSteps["${container.image.replaceAll('/','_')}-push-tag"] = { sh(pushTagCommandString) }

          
          if (container.chart) {
            chartsWithContainers += container
          }
        }
      }
    }

    stage('Collect cve scan targets') {
      def clairService = "clair-clair:6060"
      def maxCve = pipeline.cveScan.maxCve
      def maxLevel = pipeline.cveScan.maxLevel
      def ignoreCVE = pipeline.cveScan.ignore

      for (container in pipeline.builds) {
        
        if (container.script || container.commands) {
          continue
        }

        def jobName = kubeName(helmReleaseName("klar-${env.JOB_NAME}-${container.image}"))
        def imageUrl = "${defaults.docker.registry}/${container.image}:${useTag}"
        def klarJobTemplate = createKlarJob(jobName.toString(), imageUrl.toString(), maxCve.toString(), maxLevel.toString(), clairService.toString())

        parallelCveSteps[jobName] = {
          
          stage("Scan image ${imageUrl} for vulnerabilities") {

            toYamlFile(klarJobTemplate, "${pwd()}/${jobName}.yaml")
            sh("kubectl apply -f ${pwd()}/${jobName}.yaml --namespace ${defaults.jenkinsNamespace}")

            // create klar job
            def klarPod = sh returnStdout: true, script: "kubectl get pods --selector=job-name=${jobName} --output=jsonpath={.items..metadata.name} --namespace ${defaults.jenkinsNamespace}"

            def klarJobStatus = sh returnStdout: true, script: "kubectl get po ${klarPod} --output=jsonpath={.status.phase} --namespace ${defaults.jenkinsNamespace}"

            // wait for klar to finish scanning docker image
            while(klarJobStatus == "Running") { 
              klarJobStatus = sh returnStdout: true, script: "kubectl get po ${klarPod} --output=jsonpath={.status.phase} --namespace ${defaults.jenkinsNamespace}"
              continue
            }

            // get CVE report and print to console
            def klarResult = sh returnStdout: true, script: "kubectl logs ${klarPod} --namespace ${defaults.jenkinsNamespace}"
            echo(klarResult)

            def klarExitCode = sh returnStdout: true, script: "kubectl get pod ${klarPod} -o go-template='{{range .status.containerStatuses}}{{.state.terminated.exitCode}}{{end}}' --namespace ${defaults.jenkinsNamespace}"

            // fail build if max vulnerabilities found
            if ((!ignoreCVE) && (klarExitCode != "0")) {
              error("Docker image exceeds maximum vulnerabilities, check Klar CVE report for more information. The CVE report will include a link to the CVE and information on what version includes a fix")
              break
            }

            sh("kubectl delete job ${jobName} --namespace ${defaults.jenkinsNamespace}")
          }
        }
      }
    }
  }

  // build binaries
  stage('Build binaries') {
    parallel parallelBinaryBuildSteps
  }

  // build containers
  container('docker') {
    stage("Building docker files, tagging with ${gitCommit} and ${defaults.docker.testTag} and pushing.") {
      withCredentials(
        [usernamePassword(
          credentialsId: defaults.docker.credentials, 
          usernameVariable: 'DOCKER_USER',
          passwordVariable: 'DOCKER_PASSWORD')]) {
        sh('echo "$DOCKER_PASSWORD" | docker login --username $DOCKER_USER --password-stdin ' + defaults.docker.registry)
      }

      parallel parallelContainerBuildSteps
      parallel parallelTagSteps
      parallel parallelPushSteps

    }
  }

  container('yaml') {
    stage('update values.yaml files') {
      // process values yamls for modified charts
      // modify the appropriate image objects under values yaml to point to the newly tagged image
      // write back to values.yaml and stash 
      for (chart in chartsWithContainers) { 
        if (chart.tagValue) {
          replaceInYaml("${pwd()}/${chartLocation(defaults, chart.chart)}/values.yaml", 
            chart.tagValue, "${useTag}")
        } else {
          replaceInYaml("${pwd()}/${chartLocation(defaults, chart.chart)}/values.yaml", 
            chart.value, "${defaults.docker.registry}/${chart.image}:${useTag}")
        }

        stash(
          name: "${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'),
          includes: "${chartLocation(defaults, chart.chart)}/values.yaml"
        )
      }
    }
  }


  container('helm') {
    stage('Running cve scans') {
      parallel parallelCveSteps
    }
  }
}

// Tag changed repos with a stage tag
// then push to repo
def buildsStageHandler(scmVars) {
  def gitCommit = scmVars.GIT_COMMIT
  def chartsWithContainers = []

  def parallelTagSteps = [:]
  def parallelPushSteps = [:]

  executeUserScript('Executing stage \'before\' script', pipeline.stage.beforeScript)

  // get tag text
  def useTag = makeDockerTag(defaults, gitCommit)

  for (container in pipeline.builds) {
    if (!container.script && !container.commands) {
      // tag steps
      def tagCommandString = "docker tag ${defaults.docker.registry}/${container.image}:${useTag} \
       ${defaults.docker.registry}/${container.image}:${defaults.docker.stageTag}"
      parallelTagSteps["${container.image.replaceAll('/','_')}-tag"] = { sh(tagCommandString) }

      // push steps
      def pushTagCommandString = "docker push ${defaults.docker.registry}/${container.image}:${defaults.docker.stageTag}"
      parallelPushSteps["${container.image.replaceAll('/','_')}-push-tag"] = { sh(pushTagCommandString) }

      if (container.chart) {
        chartsWithContainers += container
      }
    }
  }

  container('docker') {
    stage("Tagging with ${defaults.docker.stageTag} and pushing.") {
      withCredentials(
        [usernamePassword(
          credentialsId: defaults.docker.credentials, 
          usernameVariable: 'DOCKER_USER',
          passwordVariable: 'DOCKER_PASSWORD')]) {
        sh('echo "$DOCKER_PASSWORD" | docker login --username $DOCKER_USER --password-stdin ' + defaults.docker.registry)
      }

      parallel parallelTagSteps
      parallel parallelPushSteps

    }
  }

  container('yaml') {
    stage('update values.yaml files') {
      // process values yamls for modified charts
      // modify the appropriate image objects under values yaml to point to the newly tagged image
      // write back to values.yaml and stash 
      for (chart in chartsWithContainers) {
        if (chart.tagValue) {
          replaceInYaml("${pwd()}/${chartLocation(defaults, chart.chart)}/values.yaml", 
            chart.tagValue, "${useTag}")
        } else {
          replaceInYaml("${pwd()}/${chartLocation(defaults, chart.chart)}/values.yaml", 
            chart.value, "${defaults.docker.registry}/${chart.image}:${useTag}")
        }

        stash(
          name: "${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'),
          includes: "${chartLocation(defaults, chart.chart)}/values.yaml"
        )
      }
    }
  }
}

// tag changed builds folders with prod tag
// then push to repo
def buildsProdHandler(scmVars) {
  def gitCommit = scmVars.GIT_COMMIT
  def chartsWithContainers = []

  def parallelBinaryBuildSteps = [:]
  def binaryBuildCounter = 0
  def parallelBuildSteps = [:]
  def parallelTagSteps = [:]
  def parallelPushSteps = [:]

  executeUserScript('Executing prod \'before\' script', pipeline.prod.beforeScript)

  // get tag text
  def usePrereleaseTag = makeDockerTag(defaults, gitCommit)
  def useTag = makeDockerTag(defaults, "")


  // Collect all the docker build steps as 'docker build' command string
  // for later execution in parallel
  // Also memoize the builds objects, if they are connected to in-repo charts
  container('vault') {
    stage('Collect build targets') {
      for (container in pipeline.builds) {
        if (container.script || container.commands) {
          def description = "Executing binary build ${binaryBuildCounter} using ${container.image}"
          def _container = container
          parallelBinaryBuildSteps["binary-build-${binaryBuildCounter}"] = { 
            executeUserScript(description, _container) 
          }
          binaryBuildCounter += 1 
        } else {
          // build steps
          def buildCommandString = "docker build -t \
            ${defaults.docker.registry}/${container.image}:${usePrereleaseTag} --pull " 
          if (container.buildArgs) {
            def argMap = [:]

            for (buildArg in container.buildArgs) {
              if (buildArg.secret) {
                withCredentials([string(credentialsId: defaults.vault.credentials, variable: 'VAULT_TOKEN')]) {
                  def vaultToken = env.VAULT_TOKEN
                  def secretVal = getVaultKV(
                    defaults,
                    vaultToken,
                    buildArg.secret)
                  argMap += ["${buildArg.arg}" : "${secretVal.trim()}"]
                }
              } else if (buildArg.env) {
                
                def valueFromEnv = ""
                if (env[buildArg.env]) {
                  valueFromEnv = env[buildArg.env]
                } else {
                  valueFromEnv = scmVars[buildArg.env]
                }

                argMap += ["${buildArg.arg}" : "${valueFromEnv.trim()}"]  
              } else {
                argMap += ["${buildArg.arg}" : "${buildArg.value.trim()}"]
              }
            }
            
            buildCommandString += mapToParams('--build-arg', argMap)
          }
          buildCommandString += " ${container.dockerContext} --file ${dockerfileLocation(defaults, container.locationOverride, container.context)}"
          parallelBuildSteps["${container.image.replaceAll('/','_')}-build"] = { sh(buildCommandString) }

          def tagProdString = "docker tag ${defaults.docker.registry}/${container.image}:${usePrereleaseTag} \
           ${defaults.docker.registry}/${container.image}:${defaults.docker.prodTag}"
          parallelTagSteps["${container.image.replaceAll('/','_')}-prod-tag"] = { sh(tagProdString) }
          def tagReleaseCommandString = "docker tag ${defaults.docker.registry}/${container.image}:${usePrereleaseTag} \
           ${defaults.docker.registry}/${container.image}:${useTag}"
          parallelTagSteps["${container.image.replaceAll('/','_')}-release-tag"] = { sh(tagReleaseCommandString) }


          def pushShaTagCommandString = "docker push ${defaults.docker.registry}/${container.image}:${usePrereleaseTag}"
          def pushReleaseTagCommandString = "docker push ${defaults.docker.registry}/${container.image}:${useTag}"
          def pushProdTagCommandString = "docker push ${defaults.docker.registry}/${container.image}:${defaults.docker.prodTag}"
          parallelPushSteps["${container.image.replaceAll('/','_')}-push-prod-tag"] = { sh(pushProdTagCommandString) }
          parallelPushSteps["${container.image.replaceAll('/','_')}-push-rel-tag"] = { sh(pushReleaseTagCommandString) }
          parallelPushSteps["${container.image.replaceAll('/','_')}-push-sha-tag"] = { sh(pushShaTagCommandString) }

          if (container.chart) {
            chartsWithContainers += container
          }
        }
      }
    }
  }

  // build binaries
  stage('Build binaries') {
    parallel parallelBinaryBuildSteps
  }

  container('docker') {
    stage("Building docker files, tagging with ${defaults.docker.prodTag} and pushing.") {
      withCredentials(
        [usernamePassword(
          credentialsId: defaults.docker.credentials, 
          usernameVariable: 'DOCKER_USER',
          passwordVariable: 'DOCKER_PASSWORD')]) {
        sh('echo "$DOCKER_PASSWORD" | docker login --username $DOCKER_USER --password-stdin ' + defaults.docker.registry)
      }

      parallel parallelBuildSteps
      parallel parallelTagSteps
      parallel parallelPushSteps
    }
  }

  container('yaml') {
    stage('update values.yaml files') {
      // process values yamls for modified charts
      // modify the appropriate image objects under values yaml to point to the newly tagged image
      // write back to values.yaml and stash 
      for (chart in chartsWithContainers) {
        if (chart.tagValue) {
          replaceInYaml("${pwd()}/${chartLocation(defaults, chart.chart)}/values.yaml", 
            chart.tagValue, "${useTag}")
        } else {
          replaceInYaml("${pwd()}/${chartLocation(defaults, chart.chart)}/values.yaml", 
            chart.value, "${defaults.docker.registry}/${chart.image}:${useTag}")
        }

        stash(
          name: "${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'),
          includes: "${chartLocation(defaults, chart.chart)}/values.yaml"
        )
      }
    }
  }
}

// Use helm lint to go over everything that changed 
// under /charts folder
def chartLintHandler(scmVars) { 
  def parallelLintSteps = [:]   

  // read in all appropriate versionfiles and replace Chart.yaml versions 
  // this will verify that version files had helm-valid version numbers during linting step
  container('yaml') {
    stage('Setting chart version before lint') {
      for (chart in pipeline.deployments) { 
        if (chart.chart) {
          chartVersion(defaults, chart.chart, "test.${env.BUILD_NUMBER}", scmVars.GIT_COMMIT, chart.setAppVersion)

          // grab current config object that is applicable to test section from all deployments
          def commandString = "helm lint ${chartLocation(defaults, chart.chart)}"
          parallelLintSteps["${chart.chart}-lint"] = { sh(commandString) }
        } 
      }
    }
  }

  container('helm') {
    stage('Linting charts') {
      for (chart in pipeline.deployments) {
        // unstash chart yaml changes
        unstashCheck("${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'))

        // unstash values changes if applicable
        unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))
      }

      parallel parallelLintSteps
    }
  }
}

// upload charts to helm registry
def chartProdVersion(scmVars) {
  def parallelChartSteps = [:] 

  container('yaml') {
    stage('Preparing prod version commands') {
      for (chart in pipeline.deployments) {
        if (chart.chart) {

          // modify chart version
          def chartYamlVersion = chartVersion(defaults, chart.chart, "", "", chart.setAppVersion)

          // unstash values changes if applicable
          unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))

          // package chart, send it to registry
          parallelChartSteps["${chart.chart}-upload"] = {
            withCredentials(
              [usernamePassword(
                credentialsId: defaults.helm.credentials, 
                usernameVariable: 'REGISTRY_USER',
                passwordVariable: 'REGISTRY_PASSWORD')]) {
                def registryUser = env.REGISTRY_USER
                def registryPass = env.REGISTRY_PASSWORD
                def helmCommand = """helm init --client-only
                  helm repo add pipeline https://${defaults.helm.registry}"""

                for (repo in pipeline.helmRepos) {
                  helmCommand = "${helmCommand}\nhelm repo add ${repo.name} ${repo.url}"
                }

                helmCommand = """${helmCommand}
                  helm dependency update --debug ${chartLocation(defaults, chart.chart)}
                  helm package --debug ${chartLocation(defaults, chart.chart)}
                  curl -u ${registryUser}:${registryPass} --data-binary @${chart.chart}-${chartYamlVersion}.tgz https://${defaults.helm.registry}/api/charts"""

                sh(helmCommand)
            }
          }
        }
      }
    }
  }
  
  container('helm') {
    stage('Running prod version commands') {   

      parallel parallelChartSteps
    }

    stage('Archive artifacts') {
      archiveArtifacts(artifacts: '*.tgz', allowEmptyArchive: true)
    }
  }
}

// deploy chart from source into testing namespace
def deployToTestHandler(scmVars) {
    
  container('helm') {
    stage('Deploying to test namespace') {
      def deploySteps = [:]

      // unstash kubeconfig files
      unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

      for (chart in pipeline.deployments) {
        if (chart.chart) {
          // unstash chart yaml if applicable
          unstashCheck("${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'))

          // unstash values changes if applicable
          unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))

          // deploy chart to the correct namespace
          def commandString = """
            set +x
            helm init --client-only
            helm repo add pipeline https://${defaults.helm.registry}"""

          for (repo in pipeline.helmRepos) {
            commandString = "${commandString}\nhelm repo add ${repo.name} ${repo.url}"
          }

          commandString = """${commandString}
          helm dependency update --debug ${chartLocation(defaults, chart.chart)}
          helm package --debug ${chartLocation(defaults, chart.chart)}
          helm install ${chartLocation(defaults, chart.chart)} --wait --timeout ${chart.timeout} --tiller-namespace ${pipeline.helm.namespace} --namespace ${kubeName(env.JOB_NAME)} --name ${helmReleaseName(chart.release + "-" + kubeName(env.JOB_NAME))}"""

          
          def setParams = envMapToSetParams(chart.test.values)
          commandString += setParams

          deploySteps["${chart.chart}-deploy-test"] = { 
            withEnv(
            [
              "KUBECONFIG=${env.BUILD_ID}-test.kubeconfig"
            ]) {
              sh(commandString)
            }  
          }
        }
      }

      parallel deploySteps
    }

    stage('Archive artifacts') {
      archiveArtifacts(artifacts: '*.tgz', allowEmptyArchive: true)
    }
  }
}

// deploy chart from source into staging namespace
def deployToStageHandler(scmVars) { 
  
  if (pipeline.stage.deploy) {
    container('helm') {
      stage('Deploying to stage namespace') {
        def deploySteps = [:]
        
        // unstash kubeconfig files
        unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

        for (chart in pipeline.deployments) {
          if (chart.chart) {
            // unstash chart yaml if applicable
            unstashCheck("${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'))

            // unstash values changes if applicable
            unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))

            // deploy chart to the correct namespace
            def commandString = """
            set +x
            helm init --client-only
            helm dependency update --debug ${chartLocation(defaults, chart.chart)}
            helm upgrade --install --tiller-namespace ${pipeline.helm.namespace} --wait --timeout ${chart.timeout} --namespace ${pipeline.stage.namespace} ${helmReleaseName(chart.release + "-" + pipeline.stage.namespace)} ${chartLocation(defaults, chart.chart)}""" 
            
            def setParams = envMapToSetParams(chart.stage.values)
            commandString += setParams

            deploySteps["${chart.chart}-deploy-stage"] = { 
              withEnv(
              [
                "KUBECONFIG=${env.BUILD_ID}-staging.kubeconfig"
              ]) {
                sh(commandString)
              }
            }
          }
        }

        parallel deploySteps 
        createCert(pipeline.stage.namespace)            
      }
    }
  }
}

// deploy chart from repository into prod namespace, 
// conditional on doDeploy
def deployToProdHandler(scmVars) { 
  container('helm') {
    def deploySteps = [:]
    stage('Deploying to prod namespace') {

      // unstash kubeconfig files
      unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

      for (chart in pipeline.deployments) {
        if (chart.chart) {

          // unstash chart yaml changes if applicable
          unstashCheck("${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'))

          // unstash values changes if applicable
          unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))

          chartYaml = parseYaml(readFile("${pwd()}/${chartLocation(defaults, chart.chart)}/Chart.yaml"))

          // determine if we need to deploy
          def doDeploy = false
          if (pipeline.prod.doDeploy == 'auto') {
            doDeploy = true
          } else if (pipeline.prod.doDeploy == 'versionfile') {
            if (isPathChange(defaults.versionfile, "${env.CHANGE_ID}")) {
              doDeploy = true
            }
          }

          // deploy chart to the correct namespace
          if (doDeploy) {
            def commandString = """set +x
              helm init --client-only
              helm repo add pipeline https://${defaults.helm.registry}"""

            for (repo in pipeline.helmRepos) {
              commandString = "${commandString}\nhelm repo add ${repo.name} ${repo.url}"
            }

            commandString = """${commandString}
            helm dependency update --debug ${chartLocation(defaults, chart.chart)}
            helm upgrade --install --wait --timeout ${chart.timeout} --tiller-namespace ${pipeline.helm.namespace} --repo https://${defaults.helm.registry} --version ${chartYaml.version} --namespace ${pipeline.prod.namespace} ${helmReleaseName(chart.release)} ${chart.chart} """
            
            def setParams = envMapToSetParams(chart.prod.values)
            commandString += setParams

            deploySteps["${chart.chart}-deploy-prod"] = { 
              withEnv(
              [
                "KUBECONFIG=${env.BUILD_ID}-prod.kubeconfig"
              ]) {
                sh(commandString)
              }
            }
          }
        }
      }

      parallel deploySteps
      createCert(pipeline.prod.namespace)                    
    }
  }

  executeUserScript('Executing prod \'after\' script', pipeline.prod.afterScript)
}

def pushGitChanges(scmVars) {
  
  container('helm') {
    stage('Applying and pushing git changes') {

      sh 'git clean -fdxq'
      checkout scm
      
      for (chart in pipeline.deployments) {
        if (chart.chart) {

          // unstash chart yaml changes if applicable
          unstashCheck("${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'))

          // unstash values changes if applicable
          unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))
        }
      }

      // update the versionfile
      if (!isPathChange(defaults.versionfile, "${env.CHANGE_ID}")) {
        bumpVersionfile(defaults)
      }

      withCredentials(
        [usernamePassword(
          credentialsId: defaults.github.credentials, 
          usernameVariable: 'GIT_USERNAME',
          passwordVariable: 'GIT_PASSWORD')]) {
          def scmUrl = scm.getUserRemoteConfigs()[0].getUrl()
          def repoString = buildGitRepoString(scmUrl, env.GIT_USERNAME, env.GIT_PASSWORD)
          
          def gitCommand = """
          git config --global user.email "${defaults.github.pushEmail}" 
          git config --global user.name "${defaults.github.pushUser}"
          git config push.default simple
          git add .
          git commit --allow-empty -m "${defaults.ciSkip}"
          git push ${repoString} HEAD:refs/heads/master
          """
          
          sh(gitCommand)
      }
    }
  }
}

// start any of the defined triggers, if present
def startTriggers(scmVars) {
  def triggerSteps = [:]
  try {
    configFileProvider([configFile(fileId: "${env.JOB_NAME.split('/')[0]}-dependencies", variable: 'TRIGGER_PIPELINES')]) {
      def triggerPipelines = readFile(env.TRIGGER_PIPELINES).tokenize(',').unique()
      for (trigger in triggerPipelines) {
        triggerSteps[trigger.toString()] = { build(job: "${trigger}/master", propagate: true, wait: true) }
      }
    }
  } catch (e) {
    echo("No triggers defined.")
  }

  if (triggerSteps.size() > 0) {
    parallel triggerSteps
  }
}

// run helm tests
def helmTestHandler(scmVars) {
  container('helm') {
    stage('Running helm tests') {
      
      // unstash kubeconfig files
      unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

      for (chart in pipeline.deployments) {
        if (chart.chart) {
          def commandString = """
          helm test --tiller-namespace ${pipeline.helm.namespace} --timeout ${chart.timeout} ${helmReleaseName(chart.release + "-" + kubeName(env.JOB_NAME))} --debug
          """ 

          retry(chart.retries) {
            try {
              withEnv(
                [
                  "KUBECONFIG=${env.BUILD_ID}-test.kubeconfig"
                ]) {
                sh(commandString)
              }
            } finally {
              def logString = "kubectl get pods --kubeconfig=${env.BUILD_ID}-test.kubeconfig --namespace ${kubeName(env.JOB_NAME)} -o go-template\
                  --template='{{range .items}}{{\$name := .metadata.name}}{{range \$key,\
                  \$value := .metadata.annotations}}{{\$name}} {{\$key}}:{{\$value}}+{{end}}{{end}}'\
                  | tr '+' '\\n' | grep -e helm.sh/hook:.*test-success -e helm.sh/hook:.*test-failure |\
                  cut -d' ' -f1 | while read line;\
                  do kubectl logs \$line --namespace ${kubeName(env.JOB_NAME)} --kubeconfig=${env.BUILD_ID}-test.kubeconfig;\
                  kubectl delete pod \$line --namespace ${kubeName(env.JOB_NAME)} --kubeconfig=${env.BUILD_ID}-test.kubeconfig; done"
              sh(logString)
            }
          }
        }
      }
    }
  }
}

// run test tests
def testTestHandler(scmVars) {

  for (config in pipeline.deployments) {
    if (config.test.tests) {
      for (test in config.test.tests) {
        executeUserScript('Executing test test scripts', test, ["KUBECONFIG=${pwd()}/${env.BUILD_ID}-test.kubeconfig"])
      }
    }
  }

  executeUserScript('Executing stage \'after\' script', pipeline.test.afterScript) 
}

// run staging tests
def stageTestHandler(scmVars) {

  for (config in pipeline.deployments) {
    if (config.stage.tests) {
      for (test in config.stage.tests) {
        executeUserScript('Executing staging test scripts', test, ["KUBECONFIG=${pwd()}/${env.BUILD_ID}-staging.kubeconfig"])
      }
    }
  }

  executeUserScript('Executing stage \'after\' script', pipeline.stage.afterScript) 
}

// destroy the test namespace
def destroyHandler(scmVars) {
  def destroySteps = [:]

  container('helm') {
    stage('Cleaning up test') {
      
      // unstash kubeconfig files
      unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

      echo("Contents of ${kubeName(env.JOB_NAME)} namespace:")
      sh("kubectl describe all --kubeconfig=${env.BUILD_ID}-test.kubeconfig --namespace ${kubeName(env.JOB_NAME)} || true")
      
      for (chart in pipeline.deployments) {
        if (chart.chart) {
          def commandString = "helm delete ${helmReleaseName(chart.release + "-" + kubeName(env.JOB_NAME))} --purge --tiller-namespace ${pipeline.helm.namespace}"
          destroySteps["${helmReleaseName(chart.release + "-" + kubeName(env.JOB_NAME))}"] = { 
            withEnv(
              [
                "KUBECONFIG=${env.BUILD_ID}-test.kubeconfig"
              ]) {
              sh(commandString)
            } 
          }
        }
      }

      parallel destroySteps

      sh("kubectl delete namespace ${kubeName(env.JOB_NAME)} --kubeconfig=${env.BUILD_ID}-test.kubeconfig || true")
    }
  }

  executeUserScript('Executing test \'after\' script', pipeline.test.afterScript)
}

def envMapToSetParams(envMap) {
  def setParamString = ""
  for (obj in envMap) {
    if (obj.key) {
      if (obj.secret) {
        setParamString += " --set ${obj.key}="
        withCredentials([string(credentialsId: defaults.vault.credentials, variable: 'VAULT_TOKEN')]) {
          def secretVal = getVaultKV(
            defaults,
            env.VAULT_TOKEN,
            obj.secret)

          setParamString += """'${secretVal}'"""
        }
      } else if (obj.value) {
        setParamString += " --set ${obj.key}="
        setParamString += """'${obj.value}'"""
      } 
    }
  }

  return setParamString
}

def getScriptImages() {
  // collect script containers
  def scriptContainers = []
  
  def check = {collection, item -> 
    for (existing in collection) {
      if (existing.name == item.name) {
        return
      }
    }

    collection.add(item)
  }

  if (pipeline.beforeScript) {
    check(scriptContainers, [name: containerName(pipeline.beforeScript.image),
      image: pipeline.beforeScript.image,
      shell: pipeline.beforeScript.shell])
  }
  if (pipeline.afterScript) {
    check(scriptContainers, [name: containerName(pipeline.afterScript.image),
      image: pipeline.afterScript.image,
      shell: pipeline.afterScript.shell])
  }

  if (isPRBuild || isSelfTest) {
    if (pipeline.test.afterScript) {
      check(scriptContainers, [name: containerName(pipeline.test.afterScript.image),
        image: pipeline.test.afterScript.image,
        shell: pipeline.test.afterScript.shell])
    }
    if (pipeline.test.beforeScript) {
      check(scriptContainers, [name: containerName(pipeline.test.beforeScript.image),
        image: pipeline.test.beforeScript.image,
        shell: pipeline.test.beforeScript.shell])
    }
    if (pipeline.stage.afterScript) {
      check(scriptContainers, [name: containerName(pipeline.stage.afterScript.image),
        image: pipeline.stage.afterScript.image,
        shell: pipeline.stage.afterScript.shell])
    }
    if (pipeline.stage.beforeScript) {
      check(scriptContainers, [name: containerName(pipeline.stage.beforeScript.image),
        image: pipeline.stage.beforeScript.image,
        shell: pipeline.stage.beforeScript.shell])
    }

    for (config in pipeline.deployments) {
      for (test in config.test.tests) {
        check(scriptContainers, [name: containerName(test.image),
          image: test.image,
          shell: test.shell])
      }

      for (test in config.stage.tests) {
        check(scriptContainers, [name: containerName(test.image),
          image: test.image,
          shell: test.shell])
      }
    }
  }

  if (isMasterBuild || isSelfTest) {
    if (pipeline.prod.afterScript) {
      check(scriptContainers, [name: containerName(pipeline.prod.afterScript.image),
        image: pipeline.prod.afterScript.image,
        shell: pipeline.prod.afterScript.shell])
    }
    if (pipeline.prod.beforeScript) {
      check(scriptContainers, [name: containerName(pipeline.prod.beforeScript.image),
        image: pipeline.prod.beforeScript.image,
        shell: pipeline.prod.beforeScript.shell])
    }
  }

  for (build in pipeline.builds) {
    if (build.script || build.commands ) {
      check(scriptContainers, [name: containerName(build.image),
        image: build.image,
        shell: build.shell])
    }
  }

  return scriptContainers
}

// run any kind of user script defined with :
// ---
// image: registry.com/some-image:tag
// shell: /bin/bash
// script: path/to/some-script.sh
// ---
// yaml definition 
def executeUserScript(stageText, scriptObj, additionalEnvs = []) {
  if (scriptObj) {
    stage(stageText) {
      container(containerName(scriptObj.image)) {

        // unstash kubeconfig files
        unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

        withEnv(
          [
            "DOCKER_HOST=localhost:2375",
            "PIPELINE_PROD_NAMESPACE=${pipeline.prod.namespace}",
            "PIPELINE_STAGE_NAMESPACE=${pipeline.stage.namespace}",
            "PIPELINE_TEST_NAMESPACE=${kubeName(env.JOB_NAME)}",
            "PIPELINE_BUILD_ID=${env.BUILD_ID}",
            "PIPELINE_JOB_NAME=${env.JOB_NAME}",
            "PIPELINE_BUILD_NUMBER=${env.BUILD_NUMBER}",
            "PIPELINE_WORKSPACE=${env.WORKSPACE}"
          ] + additionalEnvs) {
          if (scriptObj.commands) {
            sh(scriptObj.commands)
          }
          if (scriptObj.script) {
            sh(readFile(scriptObj.script))
          }
        }
      }
    }
  }
}

// create certificates for prod or staging
def createCert(namespace) {

  if (!pipeline.tls) {
    return
  }

  def defaultIssuerName
  def kubeconfigStr
  switch (namespace) {
    case pipeline.stage.namespace:
      defaultIssuerName = defaults.tls.stagingIssuer
      kubeconfigStr = "--kubeconfig=${env.BUILD_ID}-staging.kubeconfig"
      break
    case pipeline.prod.namespace:
      defaultIssuerName = defaults.tls.prodIssuer
      kubeconfigStr = "--kubeconfig=${env.BUILD_ID}-prod.kubeconfig"
      break
    default:
      error("Unrecognized namespace ${namespace}")
      break
  }  

  // unstash kubeconfig files
  unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

  for (tlsConf in pipeline.tls[namespace]) {
    // try to cleanup previous cert first
    sh("kubectl delete Certificate ${tlsConf.name} --namespace ${namespace} ${kubeconfigStr} || true")

    // create cert object, write to file, and install into cluster
    def cert = createCertificate(tlsConf, defaultIssuerName)
    
    toYamlFile(cert, "${pwd()}/${tlsConf.name}-cert.yaml")
    sh("kubectl apply -f ${pwd()}/${tlsConf.name}-cert.yaml --namespace ${namespace} ${kubeconfigStr}")
  }
}

return this
