import com.cloudbees.groovy.cps.NonCPS
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;

def call(Object obj, String path) {
  return toYaml(obj, path)
}

@NonCPS
def toYaml(serializedObject, path) {
  DumperOptions options = new DumperOptions();
  options.setDefaultFlowStyle(FlowStyle.BLOCK);
  options.setPrettyFlow(true);

  Yaml yaml = new Yaml(options);
  writeFile(file: path, text: yaml.dump(serializedObject))
}