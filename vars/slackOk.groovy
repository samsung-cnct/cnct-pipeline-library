def call(Map pipelineVals, String message) {
  slackSend( 
    channel: pipelineVals.slack.channel, 
    color: 'good', 
    failOnError: true, 
    message: message, 
    teamDomain: pipelineVals.slack.domain,
    tokenCredentialId: pipelineVals.slack.credentials
  )
}