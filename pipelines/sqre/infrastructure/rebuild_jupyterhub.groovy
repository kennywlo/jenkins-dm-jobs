def notify = null
node {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs()
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
  }
}

try {
  notify.started()

  node('kubectl') {
      // Needs:
      //  * Docker
      //  * Kubectl
      //  * Python (core modules only)
      //  * Git
      //  * Bash
    stage('checkout') {
      git([
        url: 'https://github.com/lsst-sqre/jupyterlabdemo',
        branch: 'master'
      ])
    }

    stage('build+deploy') {
      dir('jupyterhub') {
        kubectl.somethingSomething(
            // K8S credentials needed
        ) {
            docker.withRegistry(
                'https://index.docker.io/v1/',
                'dockerhub-sqreadmin'
            ) {
                // Default context (set explicitly with K8S_CONTEXT)
                // "sandbox" and "default" the two namespaces
                util.shColor "for i in sandbox default; do ./bld $i; done"
            }
        }
      }
    }
} catch (e) {
  // If there was an exception thrown, the build failed
  currentBuild.result = "FAILED"
  throw e
} finally {
  echo "result: ${currentBuild.result}"
  switch(currentBuild.result) {
    case null:
    case 'SUCCESS':
      notify.success()
      break
    case 'ABORTED':
      notify.aborted()
      break
    case 'FAILURE':
      notify.failure()
      break
    default:
      notify.failure()
  }
}
