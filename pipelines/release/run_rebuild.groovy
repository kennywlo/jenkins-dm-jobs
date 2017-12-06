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
  def versiondbPush = 'true'
  if (params.NO_VERSIONDB_PUSH) {
    versiondbPush = 'false'
  }

  def timelimit  = params.TIMEOUT.toInteger()
  def buildImage = 'docker.io/lsstsqre/centos:7-stackbase'
  def awsImage   = 'docker.io/lsstsqre/awscli'

  def run = {
    ws('snowflake/release') {
      stage('build') {
        withEnv([
          "EUPS_PKGROOT=${pwd()}/distrib",
          'VERSIONDB_REPO=git@github.com:lsst/versiondb.git',
          "VERSIONDB_PUSH=${versiondbPush}",
          'GIT_SSH_COMMAND=ssh -o StrictHostKeyChecking=no',
          "LSST_JUNIT_PREFIX=centos-7.py3",
          'LSST_PYTHON_VERSION=3',
          "LSST_COMPILER=gcc-system",
         ]) {
          util.insideWrap(buildImage) {
            sshagent (credentials: ['github-jenkins-versiondb']) {
              util.jenkinsWrapper()
            } // sshagent
          } // util.insideWrap
        } // withEnv
      } // stage('build')

      stage('push docs') {
        if (!params.SKIP_DOCS) {
          withCredentials([[
            $class: 'UsernamePasswordMultiBinding',
            credentialsId: 'aws-doxygen-push',
            usernameVariable: 'AWS_ACCESS_KEY_ID',
            passwordVariable: 'AWS_SECRET_ACCESS_KEY'
          ],
          [
            $class: 'StringBinding',
            credentialsId: 'doxygen-push-bucket',
            variable: 'DOXYGEN_S3_BUCKET'
          ]]) {
            withEnv([
              "EUPS_PKGROOT=${pwd()}/distrib",
              "WORKSPACE=${pwd()}",
              "HOME=${pwd()}/home",
            ]) {
              // the current iteration of the awscli container is alpine based
              // and doesn't work with util.insideWrap.  However, the aws cli
              // seems to work OK without trying to lookup the username.
              docker.image(awsImage).inside {
                util.shColor '''
                  # provides DOC_PUSH_PATH
                  . ./buildbot-scripts/settings.cfg.sh

                  aws s3 cp --recursive \
                    "${DOC_PUSH_PATH}/" \
                    "s3://${DOXYGEN_S3_BUCKET}/stack/doxygen/"
                '''
              } // util.insideWrap
            } // withEnv
          } // withCredentials
        }
      } // stage('push docs')
    } // ws
  } // run

  node('jenkins-snowflake-1') {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
} // notify.wrap
