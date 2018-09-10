node('jenkins-master') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
      changelog: false,
      poll: false
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
  }
}

notify.wrap {
  util.requireParams([
    'REFS',
    'EUPS_TAG',
    'PRODUCTS',
    'SKIP_DOCS',
  ])

  String refs      = params.REFS
  String eupsTag   = params.EUPS_TAG
  String products  = params.PRODUCTS
  Boolean skipDocs = params.SKIP_DOCS

  echo "refs: ${refs}"
  echo "[eups] tag: ${eupsTag}"
  echo "products: ${products}"
  echo "skip docs: ${skipDocs}"

  def retries = 3

  def manifestId = null

  def run = {
    stage('build') {
      retry(retries) {
        manifestId = util.runRebuild(
          parameters: [
            REFS: refs,
            PRODUCTS: products,
            SKIP_DOCS: skipDocs,
          ],
        )
      } // retry
    } // stage

    stage('eups publish') {
      retry(retries) {
        util.runPublish(
          parameters: [
            EUPSPKG_SOURCE: 'git',
            MANIFEST_ID: manifestId,
            EUPS_TAG: eupsTag,
            PRODUCTS: products,
          ],
        )
      }
    } // stage
  } // run

  try {
    timeout(time: 30, unit: 'HOURS') {
      run()
    }
  } finally {
    stage('archive') {
      def resultsFile = 'results.json'

      util.nodeTiny {
        util.dumpJson(resultsFile, [
          manifest_id: manifestId ?: null,
          git_tag: gitTag ?: null,
          eups_tag: eupsTag ?: null,
        ])

        archiveArtifacts([
          artifacts: resultsFile,
          fingerprint: true
        ])
      }
    } // stage
  } // try
} // notify.wrap
