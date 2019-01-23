def call(Map parameters = [:], body) {

  def defaultLabel = buildId('jnlp')
  def label = parameters.get('label', defaultLabel)
  def name = parameters.get('name', buildId())
  def jnlpImage = parameters.get('jnlpImage', 'jenkins/jnlp-slave:3.10-1-alpine')
  def inheritFrom = parameters.get('inheritFrom', 'base')
  def globalDefaults = parameters.get('defaults', [:])
  def idleMinutes = parameters.get('idle', 10)

  podTemplate(name: "${name}", label: label, inheritFrom: "${inheritFrom}",
      idleMinutesStr: "${idleMinutes}",
      containers: [containerTemplate(name: 'jnlp', image: "${jnlpImage}", args: '${computer.jnlpmac} ${computer.name}')]) {
    body()
  }
}