def node() {
  return "${env.JOB_NAME}-${env.BUILD_ID}".replaceAll('-','_').replaceAll('/','_')
}