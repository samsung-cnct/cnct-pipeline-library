import groovy.json.*

def call(Map defaultVals, String token, String path) {
  
  // separate path to k/v value and path inside k/v value
  def vaultPath = path.tokenize('/')[0..1].join('/')
  def valuePath = path.tokenize('/')[2..-1].join('/')

  def curl = """curl --connect-timeout 1 -s --location --header 'X-Vault-Token: ${token}' \
--cert \${VAULT_CLIENT_CERT} --cert-type PEM --key \${VAULT_CLIENT_KEY} \
--key-type PEM --cacert \${VAULT_CACERT} \
'${defaultVals.vault.server}/${defaultVals.vault.api}/${vaultPath}'""" 
  def response = sh(returnStdout: true, script: curl)
  
  return parseJSON(response, vaultPath, valuePath)
}

def parseJSON(String response, String vaultPath, String valuePath) {

  def result = new JsonSlurperClassic().parseText(response)
  if (result.errors) {
    error "Vault: " + result.errors[0].toString()
  } else if (result.data) {

    def pathParts = valuePath.tokenize('/')
    def currentObj = result.data

    for (part in pathParts) {
      if (currentObj[part]) {
        currentObj = currentObj[part]
      } else {
        error "Can't retrieve secret ${path}/${valuePath}: ${valuePath} is not under ${path}"
      }
    }  

    return currentObj.toString()
  } else {
    error "Can't retrieve secret ${vaultPath}/${valuePath}: ${response} : ${result}"
  }
}