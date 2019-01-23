def call(String prefix = '') {
  return "${prefix}${env.JOB_NAME}_${env.BUILD_NUMBER}".replaceAll('[^A-Za-z0-9]', '_')
}