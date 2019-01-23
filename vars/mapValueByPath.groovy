import com.cloudbees.groovy.cps.NonCPS

def call(String path, Map map, String value) {
  return getMapValByPath(path, map, value)
}

@NonCPS
def getMapValByPath(path, map, value) {
  List keys = path.split("\\.")
  String key = keys[0]
  
  if (keys.size() == 1) {
      map."$key" = value
      return
  }
  
  if (map."$key" == null) {
      return
  }
  
  getMapValByPath(keys.subList(1, keys.size()).join('.'), map."$key", value)
}