def call(String name) {
  return "jenkins-${name.toLowerCase().replaceAll('[^A-Za-z0-9]', '-')}"
}
