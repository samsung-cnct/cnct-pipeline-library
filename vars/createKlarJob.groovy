def call(jobName, imageUrl, maxCve, maxLevel, clairService) {
  echo("Creating job template to scan ${imageUrl} for vulnerabilities")
  def klarJobYaml = [
      "apiVersion": "batch/v1",
      "kind": "Job",
      "metadata": [
        "name": jobName
      ],
      "spec": [
        "template": [
          "spec": [
            "restartPolicy": "Never",
            "containers": [
              [
                "name": "klar",
                "image": "quay.io/samsung_cnct/klar:2.4.0",
                "args": [
                  imageUrl
                ],
                "env": [
                  [
                    "name": "CLAIR_ADDR",
                    "value": clairService
                  ],
                  [
                    "name": "CLAIR_OUTPUT",
                    "value": maxLevel
                  ],
                  [
                    "name": "CLAIR_THRESHOLD",
                    "value": maxCve
                  ]
                ]
              ]
            ],
          ]
        ],
        "backoffLimit": 0
      ]
    ]

  return klarJobYaml
}
