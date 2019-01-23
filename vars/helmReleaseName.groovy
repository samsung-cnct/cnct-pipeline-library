def call(String name) {
  if (name.length() <= 53) {
    return name;
  }
    
  return name.drop(name.length() - 53)
}