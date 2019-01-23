def call(String name) {
  try {
    unstash(name: name)
  } catch (err) {
    echo("Couldn't find stash '${name}'")
  }
}