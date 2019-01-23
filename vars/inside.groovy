def call(Map parameters = [:], body) {
  def defaultLabel = buildId('jnlp')
  def label = parameters.get('label', defaultLabel)
  
  node(label) {
    body()
  }
}