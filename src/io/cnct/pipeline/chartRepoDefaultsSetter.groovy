// /src/io/cnct/pipeline/defaultsSetter.groovy
package io.cnct.pipeline;

def setDefaults(rawSettings, defaults) {
  // check after and before scripts
  if (rawSettings.beforeScript) {
    rawSettings.beforeScript.image = 
      rawSettings.beforeScript.image ? rawSettings.beforeScript.image : defaults.images.script
    if (!rawSettings.beforeScript.shell) {
      rawSettings.beforeScript.shell = '/bin/sh'
    }
    if (!rawSettings.beforeScript.script && !rawSettings.beforeScript.commands) {
      rawSettings.beforeScript = null
    }
  }
  if (rawSettings.afterScript) {
    rawSettings.afterScript.image = 
      rawSettings.afterScript.image ? rawSettings.afterScript.image : defaults.images.script
    if (!rawSettings.afterScript.shell) {
      rawSettings.afterScript.shell = '/bin/sh'
    }
    if (!rawSettings.afterScript.script && !rawSettings.afterScript.commands) {
      rawSettings.afterScript = null
    }
  }

  // check env values
  if (!rawSettings.envValues) {
    rawSettings.envValues = []
  }

  // check pull secrets
  if (!rawSettings.pullSecrets) {
    rawSettings.pullSecrets = []
  }
  for (secret in rawSettings.pullSecrets) {
    if (!secret.name) {
      error('Pull secrets must have name')
    }
    if (!secret.server) {
      error('Pull secrets must have server')
    }
    if (!secret.username) {
      error('Pull secrets must have username')
    }
    if (!secret.email) {
      error('Pull secrets must have email')
    }
    if (!secret.password) {
      error('Pull secrets must have password')
    }
  }

  // check helmRepos settings
  if (!rawSettings.helmRepos) {
    rawSettings.helmRepos = []
  }
  for (entry in rawSettings.helmRepos) {
    if (!entry.name) {
      error('All helm repositories must have a name')
    }

    if (!entry.url) {
      error('All helm repositories must have a url')
    }
  }

  // check slack settings
  if (!rawSettings.slack) {
    rawSettings.slack = [:]
  }
  if (!rawSettings.slack.channel) {
    rawSettings.slack.channel = defaults.slack.channel
  }
  rawSettings.slack.credentials = defaults.slack.credentials
  rawSettings.slack.domain = defaults.slack.domain


  // check vault settings
  if (!rawSettings.vault) {
    rawSettings.vault = [:]
  }
  if (!rawSettings.vault.server) {
    rawSettings.vault.server = defaults.vault.server
    rawSettings.vault.credentials = defaults.vault.credentials
  }
  if (!rawSettings.vault.credentials) {
    rawSettings.vault.server = defaults.vault.server
    rawSettings.vault.credentials = defaults.vault.credentials
  }

  // check helm settings
  if (!rawSettings.helm) {
    rawSettings.helm = [:]
  }
  if (!rawSettings.helm.namespace) {
    rawSettings.helm.namespace = defaults.helm.namespace
  }

  // check cve settings
  if (!rawSettings.cveScan) {
    rawSettings.cveScan = [:]
  }
  if (!rawSettings.cveScan.maxCve) {
    rawSettings.cveScan.maxCve = defaults.cveScan.maxCve
  }
  if (!rawSettings.cveScan.maxLevel) {
    rawSettings.cveScan.maxLevel = defaults.cveScan.maxLevel
  }
  if (!rawSettings.cveScan.ignore) {
    rawSettings.cveScan.ignore = defaults.cveScan.ignore
  }

  // light checking on rootfs mappings
  // builds is equivalent to 'rootfs'
  if (rawSettings.builds && rawSettings.rootfs) {
    error("Cannot have both 'builds' and 'rootfs' sections")
  }
    
  if (rawSettings.builds) {
    for (entry in rawSettings.builds) {
      if (entry.script || entry.commands) {
        if (!entry.image) {
          error("Can't have build entries with 'script or 'command' AND no 'image'")
        }

        if (entry.context) {
          error("Can't have build entries with 'script or 'command' AND 'context'")
        }

        if (entry.dockerContext) {
          error("Can't have build entries with 'script or 'command' AND 'dockerContext'")
        }

        if (entry.context) {
          error("Can't have build entries with 'script or 'command' AND 'context'")
        }

        if (entry.buildArgs) {
          error("Can't have build entries with 'script or 'command' AND 'buildArgs'")
        }

        if (entry.value) {
          error("Can't have build entries with 'script or 'command' AND 'value'")
        }

        if (entry.tagValue) {
          error("Can't have build entries with 'script or 'command' AND 'tagValue'")
        }

        entry.shell = entry.shell ? entry.shell : defaults.shell
      } else {
        if (!entry.context) {
          error("builds items must have 'context' field")
        }

        if (!entry.dockerContext) {
          entry.dockerContext = "."
        }

        if (!entry.image) {
          error("builds items must have 'image' field")
        }

        if (entry.value && entry.tagValue) {
          error("Can't have builds items with both 'value' AND 'tagValue'")
        }

        if (!entry.buildArgs) {
          entry.buildArgs = []
        }

        for (arg in entry.buildArgs) {
          if (!arg.arg) {
            error("Each builds buildArg items must have 'arg' field")
          }
        }
      }
    }

    rawSettings.rootfs = rawSettings.builds
  }

  if (rawSettings.rootfs) {
    for (entry in rawSettings.rootfs) {
      if (entry.script || entry.commands) {
        if (!entry.image) {
          error("Can't have rootfs entries with 'script or 'command' AND no 'image'")
        }

        if (entry.context) {
          error("Can't have rootfs entries with 'script or 'command' AND 'context'")
        }

        if (entry.dockerContext) {
          error("Can't have rootfs entries with 'script or 'command' AND 'dockerContext'")
        }

        if (entry.context) {
          error("Can't have rootfs entries with 'script or 'command' AND 'context'")
        }

        if (entry.buildArgs) {
          error("Can't have rootfs entries with 'script or 'command' AND 'buildArgs'")
        }

        if (entry.value) {
          error("Can't have rootfs entries with 'script or 'command' AND 'value'")
        }

        if (entry.tagValue) {
          error("Can't have rootfs entries with 'script or 'command' AND 'tagValue'")
        }

        entry.shell = entry.shell ? entry.shell : defaults.shell
      } else {
        if (!entry.context) {
          error("rootfs items must have 'context' field")
        }

        if (!entry.dockerContext) {
          entry.dockerContext = "."
        }

        if (!entry.image) {
          error("rootfs items must have 'image' field")
        }

        if (entry.value && entry.tagValue) {
          error("Can't have builds items with both 'value' AND 'tagValue'")
        }

        if (!entry.buildArgs) {
          entry.buildArgs = []
        }

        for (arg in entry.buildArgs) {
          if (!arg.arg) {
            error("Each rootfs buildArg items must have 'arg' field")
          }
        }
      }
    }

    rawSettings.builds = rawSettings.rootfs
  }

  // check configs
  // deployments is equivalent to 'configs'
  if (rawSettings.configs && rawSettings.deployments) {
    error("Cannot have both 'configs' and 'deployments' sections")
  }
    
  if (rawSettings.deployments) {
    for (config in rawSettings.deployments) {

      if (!config.timeout) {
        config.timeout = defaults.timeout
      }

      // force true or false
      if (config.setAppVersion != true) {
        config.setAppVersion = false
      }

      if (!config.retries) {
        config.retries = defaults.retries
      }

      if (!config.test) {
        config.test = [:]
      }

      if (!config.test.values) {
        config.test.values = [:]
      }

      if (!config.test.tests) {
        config.test.tests = []
      }

      if (!config.stage) {
        config.stage = [:]
      }

      if (!config.stage.values) {
        config.stage.values = [:]
      }

      if (!config.stage.tests) {
        config.stage.tests = []
      }

      for (test in config.stage.tests) {
        test.image = test.image ? test.image : defaults.images.script
        test.shell = test.shell ? test.shell : defaults.shell
        if (!test.script && !test.commands) {
          test = null
        }
      }

      if (!config.prod) {
        config.prod = [:]
      }

      if (!config.prod.values) {
        config.prod.values = [:]
      }
    }

    rawSettings.configs = rawSettings.deployments
  } else if (rawSettings.configs) {
    for (config in rawSettings.configs) {

      if (!config.timeout) {
        config.timeout = defaults.timeout
      }

      // force true or false
      if (config.setAppVersion != true) {
        config.setAppVersion = false
      }

      if (!config.retries) {
        config.retries = defaults.retries
      }

      if (!config.test) {
        config.test = [:]
      }

      if (!config.test.values) {
        config.test.values = [:]
      }

      if (!config.test.tests) {
        config.test.tests = []
      }

      if (!config.stage) {
        config.stage = [:]
      }

      if (!config.stage.values) {
        config.stage.values = [:]
      }

      if (!config.stage.tests) {
        config.stage.tests = []
      }

      for (test in config.stage.tests) {
        test.image = test.image ? test.image : defaults.images.script
        test.shell = test.shell ? test.shell : defaults.shell
        if (!test.script && !test.commands) {
          test = null
        }
      }

      if (!config.prod) {
        config.prod = [:]
      }

      if (!config.prod.values) {
        config.prod.values = [:]
      }
    }

    rawSettings.deployments = rawSettings.configs
  } else {
    rawSettings.deployments = [:]
    rawSettings.configs = [:]
  }
  
  // check test
  if (!rawSettings.test) {
    rawSettings.test = [:]
  }

  if (!rawSettings.test.namespace) {
    rawSettings.test.cluster = defaults.targets.testCluster
  }

  if (rawSettings.test.beforeScript) {
    rawSettings.test.beforeScript.image = 
      rawSettings.test.beforeScript.image ? rawSettings.test.beforeScript.image : defaults.images.script
    if (!rawSettings.test.beforeScript.shell) {
      rawSettings.test.beforeScript.shell = '/bin/sh'
    }
    if (!rawSettings.test.beforeScript.script && !rawSettings.test.beforeScript.commands) {
      rawSettings.test.beforeScript = null
    }
  }
  if (rawSettings.test.afterScript) {
    rawSettings.test.afterScript.image = 
      rawSettings.test.afterScript.image ? rawSettings.test.afterScript.image : defaults.images.script
    if (!rawSettings.test.afterScript.shell) {
      rawSettings.test.afterScript.shell = '/bin/sh'
    }
    if (!rawSettings.test.afterScript.script && !rawSettings.test.afterScript.commands) {
      rawSettings.test.afterScript = null
    }
  }

  // check staging
  if (!rawSettings.stage) {
    rawSettings.stage = [:]
  }
  if (!rawSettings.stage.namespace) {
    rawSettings.stage.namespace = defaults.stageNamespace
  }
  if (!rawSettings.stage.cluster) {
    rawSettings.stage.cluster = defaults.targets.stagingCluster
  }
  if (!rawSettings.stage.deploy) {
    rawSettings.stage.deploy = false
  }
  if (rawSettings.stage.beforeScript) {
    rawSettings.stage.beforeScript.image = 
      rawSettings.stage.beforeScript.image ? rawSettings.stage.beforeScript.image : defaults.images.script
    if (!rawSettings.stage.beforeScript.shell) {
      rawSettings.stage.beforeScript.shell = '/bin/sh'
    }
    if (!rawSettings.stage.beforeScript.script && !rawSettings.stage.beforeScript.commands) {
      rawSettings.stage.beforeScript = null
    }
  }
  if (rawSettings.stage.afterScript) {
    rawSettings.stage.afterScript.image = 
      rawSettings.stage.afterScript.image ? rawSettings.stage.afterScript.image : defaults.images.script
    if (!rawSettings.stage.afterScript.shell) {
      rawSettings.stage.afterScript.shell = '/bin/sh'
    }
    if (!rawSettings.stage.afterScript.script && !rawSettings.stage.afterScript.commands) {
      rawSettings.stage.afterScript = null
    }
  }

  // check prod
  if (!rawSettings.prod) {
    rawSettings.prod = [:]
  }
  if (!rawSettings.prod.namespace) {
    rawSettings.prod.namespace = defaults.prodNamespace
  }
  if (!rawSettings.prod.cluster) {
    rawSettings.prod.cluster = defaults.targets.prodCluster
  }
  if (!rawSettings.prod.doDeploy) {
    rawSettings.prod.doDeploy = defaults.doDeploy
  }
  if (!(rawSettings.prod.doDeploy ==~ /auto|versionfile|none/)) {
    error("doDeploy must be either 'auto', 'off' or 'versionfile'")
  }

  if (rawSettings.prod.beforeScript) {
    rawSettings.prod.beforeScript.image = 
      rawSettings.prod.beforeScript.image ? rawSettings.prod.beforeScript.image : defaults.images.script
    if (!rawSettings.prod.beforeScript.script && !rawSettings.prod.beforeScript.commands) {
      rawSettings.prod.beforeScript = null
    }
  }
  if (rawSettings.prod.afterScript) {
    rawSettings.prod.afterScript.image = 
      rawSettings.prod.afterScript.image ? rawSettings.prod.afterScript.image : defaults.images.script
    if (!rawSettings.prod.afterScript.script && !rawSettings.prod.afterScript.commands) {
      rawSettings.prod.afterScript = null
    }
  }

  if (rawSettings.tls) {
    if (rawSettings.tls[rawSettings.stage.namespace]) {
      for (conf in rawSettings.tls[rawSettings.stage.namespace]) {
        if (!conf.name) {
          error("All tls configs have a 'name'")
        }

        if (!conf.secretName) {
          error("All tls configs have a 'secretName'")
        }

        if (!conf.dnsName) {
          error("All tls configs have a 'dnsName' section")
        }
      }
    } else {
      rawSettings.tls[rawSettings.stage.namespace] = []
    }

    if (rawSettings.tls[rawSettings.prod.namespace]) {
      for (conf in rawSettings.tls[rawSettings.prod.namespace]) {
        if (!conf.name) {
          error("All tls configs have a 'name'")
        }

        if (!conf.secretName) {
          error("All tls configs have a 'secretName'")
        }

        if (!conf.dnsName) {
          error("All tls configs have a 'dnsName' section")
        }
      }
    } else {
      rawSettings.tls[rawSettings.prod.namespace] = []
    }
  }

  return rawSettings
}

return this