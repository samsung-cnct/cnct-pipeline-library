@Grab(group='org.yaml', module='snakeyaml', version='1.17') 

import com.cloudbees.groovy.cps.NonCPS
import org.yaml.snakeyaml.Yaml

def call(String yamlText) {  
  return parseYaml(yamlText)
}

@NonCPS
def parseYaml(yamlText) {
  def yaml = new Yaml()
  return yaml.load(yamlText)
}