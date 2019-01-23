def call(String filePath, String dotPath, String value) {
  sh """
  parse.py --file ${filePath} --key-val ${dotPath}=${value} --dry-run
  """

  sh """
  parse.py --file ${filePath} --key-val ${dotPath}=${value}
  """
}