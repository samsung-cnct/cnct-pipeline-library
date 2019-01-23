def call(Map defaultVals, String packageName, String preReleaseInfo, String sha, boolean setAppVersion) {
  def versionFileContents = readFile(defaultVals.versionfile).trim()
  def versionFileComponents = versionFileContents.split('\\.')
  def finalVersion = ''

  if (versionFileComponents.length != 3) {
    error "Invalid .versionfile contents: ${versionFileContents}"
  }

  if (preReleaseInfo != "") {
    if (sha == "") {
      error "Git SHA must be specified!"
    }

    finalVersion = "${versionFileComponents[0]}.${versionFileComponents[1]}.${versionFileComponents[2]}-${preReleaseInfo}+${sha}"
  } else {
    finalVersion = "${versionFileComponents[0]}.${versionFileComponents[1]}.${versionFileComponents[2]}"
  }

  replaceInYaml("${pwd()}/${chartLocation(defaultVals, packageName)}/Chart.yaml", 
    'version', finalVersion)
  
  if (setAppVersion) {
    replaceInYaml("${pwd()}/${chartLocation(defaultVals, packageName)}/Chart.yaml", 
      'appVersion', finalVersion)
  }

  // stash the Chart.yaml
  stash(
    name: "${packageName}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'),
    includes: "${chartLocation(defaultVals, packageName)}/Chart.yaml"
  )

  return finalVersion
}