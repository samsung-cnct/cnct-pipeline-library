// /src/io/cnct/pipeline/cnctPipeline.groovy
package io.cnct.pipeline;
import java.util.regex.Matcher
import java.util.regex.Pattern

def execute() {
  agent {
    inside {
      stage('Initialize') {
        checkout scm

        pipeline = [:]
        defaults = [:] 
        
        try {
          echo('Loading pipeline defaults')
          defaults = parseYaml(libraryResource("io/cnct/pipeline/defaults.yaml"))

        } catch(FileNotFoundException e) {
          error('Could not load pipeline defaults!')
        }

        try {
          echo('Loading pipeline definition')
          pipeline = parseYaml(readFile("${pwd()}/pipeline.yaml"))
        } catch(FileNotFoundException e) {
          error "${pwd()}/pipeline.yaml not found!"
        }

        echo('Checking requirements')
        if (!fileExists(defaults.versionfile)) {
          error("Error: ${defaults.versionfile} must be present in repository root")
        }
      }
    }
  }

  switch(pipeline.type) {
    case 'chart':
      // Instantiate and execute a chart builder
      pipeline = new chartRepoDefaultsSetter().setDefaults(pipeline, defaults)
      new chartRepoBuilder().executePipeline(pipeline)
    default:
      error "Unsupported pipeline '${pipeline.pipelineType}'!"
  }  
}
