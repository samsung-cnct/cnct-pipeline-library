import com.cloudbees.groovy.cps.NonCPS

def call(String prefix, Map map) {
  return processMap(prefix, map)
}

@NonCPS
def processMap(prefix, map) {
  return map.collect { k,v -> """${prefix} ${k}='${v}'""" }.join(' ')
}