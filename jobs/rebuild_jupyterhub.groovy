import util.Common
Common.makeFolders(this)

def folder = 'sqre/infrastructure'

pipelineJob("${folder}/rebuild-jupyterhub") {
  description('Rebuilds JupyterHub with menu from current releases.')

  properties {
    rebuild {
      autoRebuild()
    }
  }

  label('jenkins-master')
  keepDependencies(true)
  concurrentBuild(false)

  def repo = SEED_JOB.scm.userRemoteConfigs.get(0).getUrl()
  def ref  = SEED_JOB.scm.getBranches().get(0).getName()

  definition {
    cpsScm {
      scm {
        git {
          remote {
            url(repo)
          }
          branch(ref)
        }
      }
      scriptPath("pipelines/${folder}/rebuild_jupyterhub.groovy")
    }
  }
}
