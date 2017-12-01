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
  def timelimit = params.TIMEOUT.toInteger()
  def awsImage  = 'docker.io/lsstsqre/awscli'

  def run = {
    ws('snowflake/release') {
      stage('publish') {
        dir('lsstsw') {
          git([
            url: 'https://github.com/lsst/lsstsw.git',
            branch: 'master',
            changelog: false,
            poll: false
          ])
        }

        dir('buildbot-scripts') {
          git([
            url: 'https://github.com/lsst-sqre/buildbot-scripts.git',
            branch: 'master',
            changelog: false,
            poll: false
          ])
        }

        def env = [
          "EUPS_PKGROOT=${pwd()}/distrib",
          "WORKSPACE=${pwd()}",
          "EUPS_USERDATA=${pwd()}/home/.eups_userdata",
        ]

        withCredentials([[
          $class: 'StringBinding',
          credentialsId: 'cmirror-s3-bucket',
          variable: 'CMIRROR_S3_BUCKET'
        ]]) {
          withEnv(env) {
            util.insideWrap(buildImage) {
              util.shColor '''
                ARGS=()
                ARGS+=('-b' "$BUILD_ID")
                ARGS+=('-t' "$TAG")
                # split whitespace separated EUPS products into separate array
                # elements by not quoting
                ARGS+=($PRODUCT)

                export EUPSPKG_SOURCE="$EUPSPKG_SOURCE"

                # setup.sh will unset $PRODUCTS
                source ./lsstsw/bin/setup.sh

                publish "${ARGS[@]}"
              '''
            }
          } // util.insideWrap
        }// withCredentials([[
      } // stage('publish')

      stage('push packages') {
        if (! params.NO_PUSH) {
          withCredentials([[
            $class: 'UsernamePasswordMultiBinding',
            credentialsId: 'aws-eups-push',
            usernameVariable: 'AWS_ACCESS_KEY_ID',
            passwordVariable: 'AWS_SECRET_ACCESS_KEY'
          ],
          [
            $class: 'StringBinding',
            credentialsId: 'eups-push-bucket',
            variable: 'EUPS_S3_BUCKET'
          ]]) {
            def env = [
              "EUPS_PKGROOT=${pwd()}/distrib",
              "WORKSPACE=${pwd()}",
              "HOME=${pwd()}/home",
            ]
            withEnv(env) {
              util.insideWrap(awsImage) {
                util.shColor '''
                  aws s3 sync "$EUPS_PKGROOT"/ s3://$EUPS_S3_BUCKET/stack/src/
                '''
              } // util.insideWrap
            } // withEnv
          }
        } else {
          echo "skipping s3 push."
        }
      } // stage('push packages')
    } // ws
  } // run

  node('jenkins-snowflake-1') {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
} // notify.wrap
