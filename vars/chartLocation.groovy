def call(defaults, packageName) {
  for (loc in defaults.deployments ) {
    if (fileExists("${loc}/${packageName}")) {
      return "${loc}/${packageName}"
    }
  }   

  error "Could not find ${packageName} in any of the ${defaults.deployments.toString()} locations"
}