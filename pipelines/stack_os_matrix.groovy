def scipipe = null

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
    scipipe = util.scipipeConfig()
  }
}

notify.wrap {
  // env vars are used instead of the params object so that "params" can be
  // set statically as env vars in calling jobs without exposing a "job param"
  // in the jenkins ui.
  util.requireEnvVars([
    'REFS',
    'BUILD_CONFIG',
    'PRODUCTS',
    'SKIP_DOCS',
    'WIPEOUT',
  ])

  def buildParams = [
    REFS:      REFS,
    PRODUCTS:  PRODUCTS,
    SKIP_DOCS: SKIP_DOCS,
  ]

  def lsstswConfigs = scipipe[BUILD_CONFIG]
  if (lsstswConfigs == null) {
    error "invalid value for BUILD_CONFIG: ${BUILD_CONFIG}"
  }

  timeout(time: 23, unit: 'HOURS') {
    stage('build') {
      util.lsstswBuildMatrix(lsstswConfigs, buildParams, WIPEOUT.toBoolean())
    }
  }
} // notify.wrap
