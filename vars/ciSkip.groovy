def call(Map defaultVals) {
    def command = """
    git log -1 | grep "${defaultVals.ciSkip}"
    """
    result = sh (script: command, returnStatus: true)
    currentBuild.result = 'SUCCESS'
    return (result == 0)
}