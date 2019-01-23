#!/usr/bin/groovy
def call(String path, String changeId = "null") { 
  def scriptVal = """
if git show-ref HEAD --head; then
  git --no-pager diff --name-only HEAD HEAD^ | grep '${path}'
  exit \$?
else
  git ls-files | grep '${path}'
  exit \$?
fi"""

  if (changeId != "null") {
    scriptVal = """
#!/usr/bin/env bash
set -eo pipefail
git fetch origin +refs/pull/${changeId}/merge
git --no-pager diff --name-only FETCH_HEAD \$(git merge-base FETCH_HEAD origin/master) | grep '${path}'
exit \$?"""
  }

  def pathChanged = sh(
    returnStatus: true,
    script: scriptVal
  )

  return (pathChanged == 0)
}