def call(String name) {
  return name.replaceAll('[^A-Za-z0-9]', '-')
}