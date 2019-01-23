def call(defaults, locationOverride, packageName) {
  if (locationOverride) {
    if (fileExists("${locationOverride}/${packageName}/Dockerfile")) {
      return "${locationOverride}/${packageName}/Dockerfile"
    } else {
      error "Could not find ${locationOverride}/${packageName}/Dockerfile"
    }
  }

  for (loc in defaults.packages) {
    if (fileExists("${loc}/${packageName}/Dockerfile")) {
      return "${loc}/${packageName}/Dockerfile"
    }
  }   

  error "Could not find ${packageName}/Dockerfile in any of the ${defaults.packages.toString()} locations"
}