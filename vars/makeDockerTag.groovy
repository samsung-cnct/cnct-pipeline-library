def call(Map defaultVals, String sha) {
  def versionFileContents = readFile(defaultVals.versionfile).trim()
  def verComponents = versionFileContents.split('\\.')

  if (verComponents.length != 3) {
    error "Invalid .versionfile contents: ${versionFileContents}"
  }

  if (sha == "") {
    return "${verComponents[0]}.${verComponents[1]}.${verComponents[2]}"
  } else {
    return "${verComponents[0]}.${verComponents[1]}.${verComponents[2]}-${sha}"
  }
}