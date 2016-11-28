import util.Common

def j = job('release/run-publish') {
  parameters {
    choiceParam('EUPSPKG_SOURCE', ['git', 'package'])
    stringParam('BUILD_ID', null, 'BUILD_ID generated by lsst_build to generate EUPS distrib packages from. Eg. b1935')
    stringParam('TAG', null, 'EUPS distrib tag name to publish. Eg. w_2016_08')
    stringParam('PRODUCT', null, 'Whitespace delimited list of EUPS products to tag.')
  }

  label('lsst-dev')
  concurrentBuild(false)

  wrappers {
    colorizeOutput('gnome-terminal')
  }

  steps {
    shell(
      '''
      #!/bin/bash -e

      export EUPSPKG_SOURCE="$EUPSPKG_SOURCE"
      # setup.sh will unset $PRODUCTS
      export PUBLISH_PRODUCTS="$PRODUCT"

      source "${HOME}/bin/setup.sh"

      publish -b "$BUILD_ID" -t "$TAG" "$PUBLISH_PRODUCTS"
      '''.replaceFirst("\n","").stripIndent()

    )
  }
}

Common.addNotification(j)