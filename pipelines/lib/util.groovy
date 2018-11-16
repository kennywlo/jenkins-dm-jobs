import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import groovy.transform.Field

/**
 * Remove leading whitespace from a multi-line String (probably a shellscript).
 */
@NonCPS
def String dedent(String text) {
  if (text == null) {
    return null
  }
  text.replaceFirst("\n","").stripIndent()
}

/**
 * Thin wrapper around {@code sh} step that strips leading whitspace.
 */
def void posixSh(script) {
  script = dedent(script)
  sh shebangerize(script, '/bin/sh -xe')
}

/**
 * Thin wrapper around {@code sh} step that strips leading whitspace.
 */
def void bash(script) {
  script = dedent(script)
  sh shebangerize(script, '/bin/bash -xe')
}

/**
 * Prepend a shebang to a String that does not already have one.
 *
 * @param script String Text to prepend a shebang to
 * @return shebangerized String
 */
@NonCPS
def String shebangerize(String script, String prog = '/bin/sh -xe') {
  if (!script.startsWith('#!')) {
    script = "#!${prog}\n${script}"
  }

  script
}
/**
 * Build a docker image, constructing the `Dockerfile` from `config`.
 *
 * Example:
 *
 *     util.buildImage(
 *       config: dockerfileText,
 *       tag: 'example/foo:bar',
 *       pull: true,
 *     )
 *
 * @param p Map
 * @param p.config String literal text of Dockerfile (required)
 * @param p.tag String name of tag to apply to generated image (required)
 * @param p.pull Boolean always pull docker base image (optional)
 */
def void buildImage(Map p) {
  requireMapKeys(p, [
    'config',
    'tag',
  ])

  String config = p.config
  String tag    = p.tag
  Boolean pull  = p.pull ?: false

  def opt = []
  opt << "--pull=${pull}"
  opt << '--build-arg D_USER="$(id -un)"'
  opt << '--build-arg D_UID="$(id -u)"'
  opt << '--build-arg D_GROUP="$(id -gn)"'
  opt << '--build-arg D_GID="$(id -g)"'
  opt << '--build-arg D_HOME="$HOME"'
  opt << '.'

  writeFile(file: 'Dockerfile', text: config)
  docker.build(tag, opt.join(' '))
} // buildImage

/**
 * Create a thin "wrapper" container around {@code image} to map uid/gid of
 * the user invoking docker into the container.
 *
 * Example:
 *
 *     util.wrapDockerImage(
 *       image: 'example/foo:bar',
 *       tag: 'example/foo:bar-local',
 *       pull: true,
 *     )
 *
 * @param p Map
 * @param p.image String name of docker base image (required)
 * @param p.tag String name of tag to apply to generated image
 * @param p.pull Boolean always pull docker base image. Defaults to `false`
 */
def void wrapDockerImage(Map p) {
  requireMapKeys(p, [
    'image',
    'tag',
  ])

  String image = p.image
  String tag   = p.tag
  Boolean pull = p.pull ?: false

  def buildDir = 'docker'
  def config = dedent("""
    FROM ${image}

    ARG     D_USER
    ARG     D_UID
    ARG     D_GROUP
    ARG     D_GID
    ARG     D_HOME

    USER    root
    RUN     mkdir -p "\$(dirname \$D_HOME)"
    RUN     groupadd -g \$D_GID \$D_GROUP
    RUN     useradd -d \$D_HOME -g \$D_GROUP -u \$D_UID \$D_USER

    USER    \$D_USER
    WORKDIR \$D_HOME
  """)

  // docker insists on recusrively checking file access under its execution
  // path -- so run it from a dedicated dir
  dir(buildDir) {
    buildImage(
      config: config,
      tag: tag,
      pull: pull,
    )

    deleteDir()
  }
} // wrapDockerImage

/**
 * Invoke block inside of a "wrapper" container.  See: wrapDockerImage
 *
 * Example:
 *
 *     util.insideDockerWrap(
 *       image: 'example/foo:bar',
 *       args: '-e HOME=/baz',
 *       pull: true,
 *     )
 *
 * @param p Map
 * @param p.image String name of docker image (required)
 * @param p.args String docker run args (optional)
 * @param p.pull Boolean always pull docker image. Defaults to `false`
 * @param run Closure Invoked inside of wrapper container
 */
def insideDockerWrap(Map p, Closure run) {
  requireMapKeys(p, [
    'image',
  ])

  String image = p.image
  String args  = p.args ?: null
  Boolean pull = p.pull ?: false

  def imageLocal = "${image}-local"

  wrapDockerImage(
    image: image,
    tag: imageLocal,
    pull: pull,
  )

  docker.image(imageLocal).inside(args) { run() }
}

/**
 * Join multiple String args togther with '/'s to resemble a filesystem path.
 */
// The groovy String#join method is not working under the security sandbox
// https://issues.jenkins-ci.org/browse/JENKINS-43484
@NonCPS
def String joinPath(String ... parts) {
  String text = null

  def n = parts.size()
  parts.eachWithIndex { x, i ->
    if (text == null) {
      text = x
    } else {
      text += x
    }

    if (i < (n - 1)) {
      text += '/'
    }
  }

  return text
} // joinPath

/**
 * Serialize a Map to a JSON string and write it to a file.
 *
 * @param filename output filename
 * @param data Map to serialize
 */
@NonCPS
def dumpJson(String filename, Map data) {
  def json = new groovy.json.JsonBuilder(data)
  def pretty = groovy.json.JsonOutput.prettyPrint(json.toString())
  echo pretty
  writeFile file: filename, text: pretty
}

/**
 * Parse a JSON string.
 *
 * @param data String to parse.
 * @return Object parsed JSON object
 */
@NonCPS
def slurpJson(String data) {
  new groovy.json.JsonSlurperClassic().parseText(data)
}

/**
 * Run a command, that is assumed to return JSON, and parse the stdout.
 *
 * @param script String shell script to execute.
 * @return Object parsed JSON object
 */
def shJson(String script) {
  def stdout = sh(returnStdout: true, script: script).trim()
  slurpJson(stdout)
}

/**
 * Create an EUPS distrib tag
 *
 * Example:
 *
 *     util.runPublish(
 *       parameters: [
 *         EUPSPKG_SOURCE: 'git',
 *         MANIFEST_ID: manifestId,
 *         EUPS_TAG: eupsTag,
 *         PRODUCTS: products,
 *       ],
 *     )
 *
 * @param p Map
 * @param p.job String job to trigger. Defaults to `release/run-publish`.
 * @param p.parameters.EUPSPKG_SOURCE String
 * @param p.parameters.MANIFEST_ID String
 * @param p.parameters.EUPS_TAG String
 * @param p.parameters.PRODUCTS String
 * @param p.parameters.TIMEOUT String Defaults to `'1'`.
 */
def void runPublish(Map p) {
  requireMapKeys(p, [
    'parameters',
  ])
  def useP = [
    job: 'release/run-publish',
  ] + p

  requireMapKeys(p.parameters, [
    'EUPSPKG_SOURCE',
    'MANIFEST_ID',
    'EUPS_TAG',
    'PRODUCTS',
  ])
  useP.parameters = [
    TIMEOUT: '1' // should be string
  ] + p.parameters

  build(
    job: useP.job,
    parameters: [
      string(name: 'EUPSPKG_SOURCE', value: useP.parameters.EUPSPKG_SOURCE),
      string(name: 'MANIFEST_ID', value: useP.parameters.MANIFEST_ID),
      string(name: 'EUPS_TAG', value: useP.parameters.EUPS_TAG),
      string(name: 'PRODUCTS', value: useP.parameters.PRODUCTS),
      string(name: 'TIMEOUT', value: useP.parameters.TIMEOUT.toString()),
    ],
  )
} // runPublish

/**
 * Run a lsstsw build.
 *
 * @param image String
 * @param label Node label to run on
 * @param compiler String compiler to require and setup, if nessicary.
 * @param python Python major revsion to build with. Eg., '2' or '3'
 * @param wipteout Delete all existing state before starting build
 */
def lsstswBuild(
  Map buildParams,
  String image,
  String label,
  String compiler,
  String python,
  String slug,
  Boolean wipeout=false
) {
  def run = {
    buildParams += [
      LSST_COMPILER:       compiler,
      LSST_JUNIT_PREFIX:   slug,
      LSST_PYTHON_VERSION: python,
    ]

    jenkinsWrapper(buildParams)
  } // run

  def runDocker = {
    insideDockerWrap(
      image: image,
      pull: true,
    ) {
      run()
    }
  } // runDocker

  def runEnv = { doRun ->
    timeout(time: 8, unit: 'HOURS') {
      // use different workspace dirs for python 2/3 to avoid residual state
      // conflicts
      try {
        dir(slug) {
          if (wipeout) {
            deleteDir()
          }

          doRun()
        } // dir
      } finally {
        // needs to be called in the parent dir of jenkinsWrapper() in order to
        // add the slug as a prefix to the archived files.
        jenkinsWrapperPost(slug)
      }
    } // timeout
  } // runEnv

  def agent = null
  def task = null
  if (image) {
    agent = 'docker'
    task = { runEnv(runDocker) }
  } else {
    agent = label
    task = { runEnv(run) }
  }

  node(agent) {
    task()
  } // node
} // lsstswBuild

/**
 * Run a build using ci-scripts/jenkins_wrapper.sh
 *
 * Required keys are listed below. Any additional keys will also be set as env
 * vars.
 * @param buildParams.LSST_REFS String
 * @param buildParams.LSST_PRODUCTS String
 */
def void jenkinsWrapper(Map buildParams) {
  // minimum set of required keys -- additional are allowed
  requireMapKeys(buildParams, [
    'LSST_COMPILER',
    'LSST_PRODUCTS',
    'LSST_REFS',
  ])
  def scipipe = scipipeConfig()

  buildParams = [
    // XXX this should be renamed in lsstsw to make it clear that its setting a
    // github repo slug
    REPOSFILE_REPO: scipipe.repos.github_repo,
  ] + buildParams

  def cwd     = pwd()
  def homeDir = "${cwd}/home"

  try {
    dir('lsstsw') {
      cloneLsstsw()
    }

    dir('ci-scripts') {
      cloneCiScripts()
    }

    // workspace relative dir for dot files to prevent bleed through between
    // jobs and subsequent builds.
    emptyDirs([homeDir])

    // cleanup *all* conda cached package info
    [
      'lsstsw/miniconda/conda-meta',
      'lsstsw/miniconda/pkgs',
    ].each { it ->
      dir(it) {
        deleteDir()
      }
    }

    def buildEnv = [
      "WORKSPACE=${cwd}",
      "HOME=${homeDir}",
      "EUPS_USERDATA=${homeDir}/.eups_userdata",
    ]

    // Map -> List
    buildParams.each { pair ->
      buildEnv += pair.toString()
    }

    // setup env vars to use a conda mirror
    withCondaMirrorEnv {
      withEnv(buildEnv) {
        bash './ci-scripts/jenkins_wrapper.sh'
      }
    }
  } finally {
    withEnv(["WORKSPACE=${cwd}"]) {
      bash '''
        if hash lsof 2>/dev/null; then
          Z=$(lsof -d 200 -t)
          if [[ ! -z $Z ]]; then
            kill -9 $Z
          fi
        else
          echo "lsof is missing; unable to kill rebuild related processes."
        fi

        rm -rf "${WORKSPACE}/lsstsw/stack/.lockDir"
      '''
    }
  } // try
} // jenkinsWrapper

def jenkinsWrapperPost(String baseDir = null) {
  def lsstsw = 'lsstsw'

  if (baseDir) {
    lsstsw = "${baseDir}/${lsstsw}"
  }

  // note that archive does not like a leading `./`
  def lsstsw_build_dir = "${lsstsw}/build"
  def manifestPath = "${lsstsw_build_dir}/manifest.txt"
  def statusPath = "${lsstsw_build_dir}/status.yaml"
  def archive = [
    manifestPath,
    statusPath,
  ]
  def record = [
    '*.log',
    '*.failed',
  ]

  try {
    if (fileExists(statusPath)) {
      def status = readYaml(file: statusPath)

      def products = status['built']
      // if there is a "failed_at" product, check it for a junit file too
      if (status['failed_at']) {
        products << status['failed_at']
      }

      def reports = []
      products.each { item ->
        def name = item['name']
        def xml = "${lsstsw_build_dir}/${name}/tests/.tests/pytest-${name}.xml"
        reports << xml

        record.each { pattern ->
          archive += "${lsstsw_build_dir}/${name}/**/${pattern}"
        }
      }

      if (reports) {
        // note that junit will ignore files with timestamps before the start
        // of the build
        junit([
          testResults: reports.join(', '),
          allowEmptyResults: true,
        ])

        archive += reports
      }
    }
  } catch (e) {
    // As a last resort, find product build dirs with a wildcard.  This might
    // match logs for products that _are not_ part of the current build.
    record.each { pattern ->
      archive += "${lsstsw_build_dir}/**/${pattern}"
    }
    throw e
  } finally {
    archiveArtifacts([
      artifacts: archive.join(', '),
      allowEmptyArchive: true,
      fingerprint: true
    ])
  } // try
} // jenkinsWrapperPost

/**
 * Parse manifest id out of a manifest.txt format String.
 *
 * @param manifest.txt as a String
 * @return manifestId String
 */
@NonCPS
def String parseManifestId(String manifest) {
  def m = manifest =~ /(?m)^BUILD=(b.*)/
  m ? m[0][1] : null
}

/**
 * Validate that required parameters were passed from the job and raise an
 * error on any that are missing.
 *
 * @param rps List of required job parameters
 */
def void requireParams(List rps) {
  rps.each { it ->
    if (params.get(it) == null) {
      error "${it} parameter is required"
    }
  }
}

/**
 * Validate that required env vars were passed from the job and raise an
 * error on any that are missing.
 *
 * @param rev List of required env vars
 */
def void requireEnvVars(List rev) {
  // note that `env` isn't a map and #get doesn't work as expected
  rev.each { it ->
    if (env."${it}" == null) {
      error "${it} envirnoment variable is required"
    }
  }
}

/**
 * Validate that map contains AT LEAST the specified list of keys and raise
 * an error on any that are missing.
 *
 * @param check Map object to inspect
 * @param key List of required map keys
 */
def void requireMapKeys(Map check, List keys) {
  keys.each { k ->
    if (! check.containsKey(k)) {
      error "${k} key is missing from Map"
    }
  }
}

/**
 * Empty directories by deleting and recreating them.
 *
 * @param dirs List of directories to empty
*/
def void emptyDirs(List eds) {
  eds.each { d ->
    dir(d) {
      deleteDir()
      // a file operation is needed to cause the dir() step to recreate the dir
      writeFile(file: '.dummy', text: '')
    }
  }
}

/**
 * Ensure directories exist and create any that are absent.
 *
 * @param dirs List of directories to ensure/create
*/
def void createDirs(List eds) {
  eds.each { d ->
    dir(d) {
      // a file operation is needed to cause the dir() step to recreate the dir
      writeFile(file: '.dummy', text: '')
    }
  }
}

/**
 * XXX this method was developed during the validate_drp conversion to pipeline
 * but is currently unusued.  It has been preserved as it might be useful in
 * other jobs.
 *
 * Write a copy of `manifest.txt`.
 *
 * @param rebuildId String `run-rebuild` build id.
 * @param filename String Output filename.
 */
def void getManifest(String rebuildId, String filename) {
  def manifest_artifact = 'lsstsw/build/manifest.txt'
  def buildJob          = 'release/run-rebuild'

  step([$class: 'CopyArtifact',
        projectName: buildJob,
        filter: manifest_artifact,
        selector: [
          $class: 'SpecificBuildSelector',
          buildNumber: rebuildId // wants a string
        ],
      ])

  def manifest = readFile manifest_artifact
  writeFile(file: filename, text: manifest)
} // getManifest

/**
 * Run the `github-tag-release` script from `sqre-codekit` with parameters.
 *
 * Example:
 *
 *     util.githubTagRelease(
 *       options: [
 *         '--dry-run': true,
 *         '--org': 'myorg'
 *         '--manifest': 'b1234',
 *         '--eups-tag': 'v999_0_0',
 *       ],
 *       args: ['999.0.0'],
 *     )
 *
 * @param p Map
 * @param p.options Map CLI --<options>. Required. See `makeCliCmd`
 * @param p.options.'--org' String Required.
 * @param p.options.'--manifest' String Required.
 * @param p.options.'--eups-tag' String Required.
 * @param p.args List Eg., `[<git tag>]` Required.
 */
def void githubTagRelease(Map p) {
  requireMapKeys(p, [
    'args',
    'options',
  ])
  requireMapKeys(p.options, [
    '--org',
    '--manifest',
  ])

  def prog = 'github-tag-release'
  def defaultOptions = [
    '--debug': true,
    '--dry-run': true,
    '--token': '$GITHUB_TOKEN',
    '--user': 'sqreadmin',
    '--email': 'sqre-admin@lists.lsst.org',
    '--allow-team': ['Data Management', 'DM Externals'],
    '--external-team': 'DM Externals',
    '--deny-team': 'DM Auxilliaries',
    '--fail-fast': true,
  ]

  runCodekitCmd(prog, defaultOptions, p.options, p.args)
} // githubTagRelease

/**
 * Run the `github-tag-teams` script from `sqre-codekit` with parameters.
 *
 * Example:
 *
 *     util.githubTagTeams(
 *       options: [
 *         '--dry-run': true,
 *         '--org': 'myorg',
 *         '--tag': '999.0.0',
 *       ],
 *     )
 *
 * @param p Map
 * @param p.options Map CLI --<options>. Required. See `makeCliCmd`
 * @param p.options.'--org' String Required.
 * @param p.options.'--tag' String|List Required.
 */
def void githubTagTeams(Map p) {
  requireMapKeys(p, [
    'options',
  ])
  requireMapKeys(p.options, [
    '--org',
    '--tag',
  ])
  def prog = 'github-tag-teams'
  def defaultOptions = [
    '--debug': true,
    '--dry-run': true,
    '--token': '$GITHUB_TOKEN',
    '--user': 'sqreadmin',
    '--email': 'sqre-admin@lists.lsst.org',
    '--allow-team': 'DM Auxilliaries',
    '--deny-team': 'DM Externals',
    '--ignore-existing-tag': true,
  ]

  runCodekitCmd(prog, defaultOptions, p.options, null)
} // githubTagTeams

/**
 * Run the `github-get-ratelimit` script from `sqre-codekit`.
 *
 */
def void githubGetRatelimit() {
  def prog = 'github-get-ratelimit'
  def defaultOptions = [
    '--token': '$GITHUB_TOKEN',
  ]

  runCodekitCmd(prog, defaultOptions, null, null)
}

/**
 * Run a codekit cli command.
 *
 * @param prog String see `makeCliCmd`
 * @param defaultOptions Map see `makeCliCmd`
 * @param options Map see `makeCliCmd`
 * @param args List see `makeCliCmd`
 */
def void runCodekitCmd(
  String prog,
  Map defaultOptions,
  Map options,
  List args,
  Integer timelimit = 30
) {
  def cliCmd = makeCliCmd(prog, defaultOptions, options, args)

  timeout(time: timelimit, unit: 'MINUTES') {
    insideCodekit {
      bash cliCmd
    }
  }
} // runCodekitCmd

/**
 * Generate a string for executing a system command with optional flags and/or
 * arguments.
 *
 * @param prog String command to run.
 * @param defaultOptions Map command option flags.
 * @param options Map script option flags.  These are merged with
 * defaultOptions.  Truthy values are considered as an active flag while the
 * literal `true` constant indicates a boolean flag.  Falsey values result in
 * the flag being omitted.  Lists/Arrays result in the flag being specified
 * multiple times.
 * @param args List verbatium arguments to pass to command.
 * @return String complete cli command
 */
def String makeCliCmd(
  String prog,
  Map defaultOptions,
  Map options,
  List args
) {
  def useOpts = [:]

  if (defaultOptions) {
    useOpts = defaultOptions
  }
  if (options) {
    useOpts += options
  }

  cmd = [prog]

  if (useOpts) {
    cmd += mapToCliFlags(useOpts)
  }
  if (args) {
    cmd += listToCliArgs(args)
  }

  return cmd.join(' ')
} // makeCliCmd

/**
 * Run block inside a container with sqre-codekit installed and a github oauth
 * token defined as `GITHUB_TOKEN`.
 *
 * @param run Closure Invoked inside of node step
 */
def void insideCodekit(Closure run) {
  insideDockerWrap(
    image: defaultCodekitImage(),
    pull: true,
  ) {
    withGithubAdminCredentials {
      run()
    }
  } // insideDockerWrap
} // insideCodekit

/**
 * Convert a map of command line flags (keys) and values into a string suitable
 * to be passed on "the cli" to a program
 *
 * @param opt Map script option flags
 */
def String mapToCliFlags(Map opt) {
  def flags = []

  opt.each { k,v ->
    if (v) {
      if (v == true) {
        // its a boolean flag
        flags += k
      } else {
        // its a flag with an arg
        if (v instanceof List) {
          // its a flag with multiple values
          v.each { nested ->
            flags += "${k} \"${nested}\""
          }
        } else {
          // its a flag with a single value
          flags += "${k} \"${v}\""
        }
      }
    }
  }

  return flags.join(' ')
} // mapToCliFlags

/**
 * Convert a List of command line args into a string suitable
 * to be passed on "the cli" to a program
 *
 * @param args List of command arguments
 * @return String of arguments
 */
def String listToCliArgs(List args) {
  return args.collect { "\"${it}\"" }.join(' ')
}

/**
 * Run block with a github oauth token defined as `GITHUB_TOKEN`.
 *
 * @param run Closure Invoked inside of node step
 */
def void withGithubAdminCredentials(Closure run) {
  withCredentials([[
    $class: 'StringBinding',
    credentialsId: 'github-api-token-sqreadmin',
    variable: 'GITHUB_TOKEN'
  ]]) {
    run()
  } // withCredentials
}

/**
 * Run trivial execution time block
 *
 * @param run Closure Invoked inside of node step
 */
def void nodeTiny(Closure run) {
  node('jenkins-master') {
    timeout(time: 5, unit: 'MINUTES') {
      run()
    }
  }
}

/**
 * Execute a multiple multiple lsstsw builds using different configurations.
 *
 * @param matrixConfig List of lsstsw build configurations
 * @param buildParams Map of params/env vars for jenkins_wrapper.sh
 * @param wipeout Boolean wipeout the workspace build starting the build
 */
def lsstswBuildMatrix(
  List matrixConfig,
  Map buildParams,
  Boolean wipeout=false
) {
  def matrix = [:]

  // XXX validate config
  matrixConfig.each { lsstswConfig ->
    def slug = lsstswConfigSlug(lsstswConfig)

    matrix[slug] = {
      lsstswBuild(
        buildParams,
        lsstswConfig.image,
        lsstswConfig.label,
        lsstswConfig.compiler,
        lsstswConfig.python,
        slug,
        wipeout
      )
    }
  }

  parallel matrix
} // lsstswBuildMatrix

/**
 * Clone lsstsw git repo
 */
def void cloneLsstsw() {
  def scipipe = scipipeConfig()

  gitNoNoise(
    url: githubSlugToUrl(scipipe.lsstsw.github_repo),
    branch: scipipe.lsstsw.git_ref,
  )
}

/**
 * Clone ci-scripts git repo
 */
def void cloneCiScripts() {
  def scipipe = scipipeConfig()

  gitNoNoise(
    url: githubSlugToUrl(scipipe.ciscripts.github_repo),
    branch: scipipe.ciscripts.git_ref,
  )
}

/**
 * Clone git repo without generating a jenkins build changelog
 */
def void gitNoNoise(Map args) {
  git([
    url: args.url,
    branch: args.branch,
    changelog: false,
    poll: false
  ])
}

/**
 * Parse yaml file into object -- parsed files are memoized.
 *
 * @param file String file to parse
 * @return yaml Object
 */
// The @Memoized decorator seems to break pipeline serialization and this
// method can not be labeled as @NonCPS.
@Field Map yamlCache = [:]
def Object readYamlFile(String file) {
  def yaml = yamlCache[file] ?: readYaml(text: readFile(file))
  yamlCache[file] = yaml
  return yaml
}

/**
 * Build a multi-configuration matrix of eups tarballs.
 *
 * Example:
 *
 *     util.buildTarballMatrix(
 *       tarballConfigs: config.tarball.build_config,
 *       parameters: [
 *         PRODUCTS: tarballProducts,
 *         SMOKE: true,
 *         RUN_SCONS_CHECK: true,
 *         PUBLISH: true,
 *       ],
 *       retries: retries,
 *     )
 *
 * @param p Map
 * @param p.tarballConfigs List
 * @param p.parameters.PRODUCTS String
 * @param p.parameters.EUPS_TAG String
 * @param p.retries Integer Defaults to `1`.
 */
def void buildTarballMatrix(Map p) {
  requireMapKeys(p, [
    'tarballConfigs',
    'parameters',
  ])
  p = [
    retries: 1,
  ] + p

  requireMapKeys(p.parameters, [
    'PRODUCTS',
    'EUPS_TAG',
  ])

  def platform = [:]

  p.tarballConfigs.each { item ->
    def displayName = item.display_name ?: item.label
    def displayCompiler = item.display_compiler ?: item.compiler

    def slug = "miniconda${item.python}"
    slug += "-${item.miniver}-${item.lsstsw_ref}"

    def tarballBuild = {
      retry(p.retries) {
        build job: 'release/tarball',
          parameters: [
            string(name: 'PRODUCTS', value: p.parameters.PRODUCTS),
            string(name: 'EUPS_TAG', value: p.parameters.EUPS_TAG),
            booleanParam(name: 'SMOKE', value: p.parameters.SMOKE),
            booleanParam(
              name: 'RUN_SCONS_CHECK',
              value: p.parameters.RUN_SCONS_CHECK
            ),
            booleanParam(name: 'PUBLISH', value: p.parameters.PUBLISH),
            booleanParam(name: 'WIPEOUT', value: false),
            string(name: 'TIMEOUT', value: item.timelimit.toString()), // hours
            string(name: 'IMAGE', value: nullToEmpty(item.image)),
            string(name: 'LABEL', value: item.label),
            string(name: 'COMPILER', value: item.compiler),
            string(name: 'PYTHON_VERSION', value: item.python),
            string(name: 'MINIVER', value: item.miniver),
            string(name: 'LSSTSW_REF', value: item.lsstsw_ref),
            string(name: 'OSFAMILY', value: item.osfamily),
            string(name: 'PLATFORM', value: item.platform),
          ]
      } // retry
    }

    platform["${displayName}.${displayCompiler}.${slug}"] = {
      if (item.allow_fail) {
        try {
          tarballBuild()
        } catch (e) {
          echo "giving up on build but suppressing error"
          echo e.toString()
        }
      } else {
        tarballBuild()
      }
    } // platform
  } // each

  parallel platform
} // buildTarballMatrix

/**
 * Convert null to empty string; pass through valid strings
 *
 * @param s String string to process
 */
@NonCPS
def String nullToEmpty(String s) {
  if (!s) { s = '' }
  s
}

/**
 * Convert an empty string to null; pass through valid strings
 *
 * @param s String string to process
 */
@NonCPS
def String emptyToNull(String s) {
  if (s == '') { s = null }
  s
}

/**
 * Convert UNIX epoch (seconds) to a UTC formatted date/time string.
 * @param epoch Integer count of seconds since UNIX epoch
 * @return String UTC formatted date/time string
 */
@NonCPS
def String epochToUtc(Integer epoch) {
  def unixTime = Instant.ofEpochSecond(epoch)
  instantToUtc(unixTime)
}

/**
 * Convert UNIX epoch (milliseconds) to a UTC formatted date/time string.
 * @param epoch Integer count of milliseconds since UNIX epoch
 * @return String UTC formatted date/time string
 */
@NonCPS
def String epochMilliToUtc(Long epoch) {
  def unixTime = Instant.ofEpochMilli(epoch)
  instantToUtc(unixTime)
}

/**
 * Convert java.time.Instant objects to a UTC formatted date/time string.
 * @param moment java.time.Instant object
 * @return String UTC formatted date/time string
 */
@NonCPS
def String instantToUtc(Instant moment) {
  def utcFormat = DateTimeFormatter
                    .ofPattern("yyyyMMdd'T'hhmmssX")
                    .withZone(ZoneId.of('UTC') )

  utcFormat.format(moment)
}

/**
 * Run librarian-puppet on the current directory via a container
 *
 * @param cmd String librarian-puppet arguments; defaults to 'install'
 * @param tag String tag of docker image to use.
 */
def void librarianPuppet(String cmd='install', String tag='2.2.3') {
  insideDockerWrap(
    image: "lsstsqre/cakepan:${tag}",
    args: "-e HOME=${pwd()}",
    pull: true,
  ) {
    bash "librarian-puppet ${cmd}"
  }
}

/**
 * run documenteer doc build
 *
 * @param args.docTemplateDir String path to sphinx template clone (required)
 * @param args.eupsPath String path to EUPS installed productions (optional)
 * @param args.eupsTag String tag to setup. defaults to 'current'
 * @param args.docImage String defaults to: 'lsstsqre/documenteer-base'
 * @param args.docPull Boolean defaults to: `false`
 */
def runDocumenteer(Map args) {
  def argDefaults = [
    docImage: 'lsstsqre/documenteer-base',
    docPull: false,
    eupsTag: 'current',
  ]
  args = argDefaults + args

  def homeDir = "${pwd()}/home"
  emptyDirs([homeDir])

  def docEnv = [
    "HOME=${homeDir}",
    "EUPS_TAG=${args.eupsTag}",
  ]

  if (args.eupsPath) {
    docEnv += "EUPS_PATH=${args.eupsPath}"
  }

  withEnv(docEnv) {
    insideDockerWrap(
      image: args.docImage,
      pull: args.docPull,
    ) {
      dir(args.docTemplateDir) {
        bash '''
          source /opt/lsst/software/stack/loadLSST.bash
          export PATH="${HOME}/.local/bin:${PATH}"
          pip install --upgrade --user -r requirements.txt
          setup -r . -t "$EUPS_TAG"
          build-stack-docs -d . -v
        '''
      } // dir
    } // insideDockerWrap
  } // withEnv
} // runDocumenteer

/**
 * run ltd-mason-travis to push a doc build
 *
 * @param args.eupsTag String tag to setup. Eg.: 'current', 'b1234'
 * @param args.repoSlug String github repo slug. Eg.: 'lsst/pipelines_lsst_io'
 * @param args.product String LTD product name., Eg.: 'pipelines'
 */
def ltdPush(Map args) {
  def masonImage = 'lsstsqre/ltd-mason'

  withEnv([
    "LTD_MASON_BUILD=true",
    "LTD_MASON_PRODUCT=${args.ltdProduct}",
    "LTD_KEEPER_URL=https://keeper.lsst.codes",
    "LTD_KEEPER_USER=travis",
    "TRAVIS_PULL_REQUEST=false",
    "TRAVIS_REPO_SLUG=${args.repoSlug}",
    "TRAVIS_BRANCH=${args.eupsTag}",
  ]) {
    withCredentials([[
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'ltd-mason-aws',
      usernameVariable: 'LTD_MASON_AWS_ID',
      passwordVariable: 'LTD_MASON_AWS_SECRET',
    ],
    [
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'ltd-keeper',
      usernameVariable: 'LTD_KEEPER_USER',
      passwordVariable: 'LTD_KEEPER_PASSWORD',
    ]]) {
      docker.image(masonImage).inside {
        // expect that the service will return an HTTP 502, which causes
        // ltd-mason-travis to exit 1
        sh '''
        /usr/bin/ltd-mason-travis --html-dir _build/html --verbose || true
        '''
      } // .inside
    } // withCredentials
  } //withEnv
} // ltdPush

/**
 * run `release/run-rebuild` job and parse result
 *
 * Example:
 *
 *     manifestId = util.runRebuild(
 *       parameters: [
 *         PRODUCTS: products,
 *         BUILD_DOCS: true,
 *       ],
 *     )
 *
 * @param p Map
 * @param p.job String job to trigger. Defaults to `release/run-rebuild`.
 * @param p.parameters Map
 * @param p.parameters.REFS String Defaults to `''`.
 * @param p.parameters.PRODUCTS String Defaults to `''`.
 * @param p.parameters.BUILD_DOCS Boolean Defaults to `false`.
 * @param p.parameters.TIMEOUT String Defaults to `'8'`.
 * @param p.parameters.PREP_ONLY Boolean Defaults to `false`.
 * @return manifestId String
 */
def String runRebuild(Map p) {
  def useP = [
    job: 'release/run-rebuild',
  ] + p

  useP.parameters = [
    REFS: '',  // null is not a valid value for a string param
    PRODUCTS: '',
    BUILD_DOCS: false,
    TIMEOUT: '8', // should be String
    PREP_ONLY: false,
  ] + p.parameters

  def result = build(
    job: useP.job,
    parameters: [
      string(name: 'REFS', value: useP.parameters.REFS),
      string(name: 'PRODUCTS', value: useP.parameters.PRODUCTS),
      booleanParam(name: 'BUILD_DOCS', value: useP.parameters.BUILD_DOCS),
      string(name: 'TIMEOUT', value: useP.parameters.TIMEOUT), // hours
      booleanParam(name: 'PREP_ONLY', value: useP.parameters.PREP_ONLY),
    ],
    wait: true,
  )

  nodeTiny {
    manifestArtifact = 'lsstsw/build/manifest.txt'

    step([$class: 'CopyArtifact',
          projectName: useP.job,
          filter: manifestArtifact,
          selector: [
            $class: 'SpecificBuildSelector',
            buildNumber: result.id,
          ],
        ])

    def manifestId = parseManifestId(readFile(manifestArtifact))
    echo "parsed manifest id: ${manifestId}"
    return manifestId
  } // nodeTiny
} // runRebuild

/*
 * Convert github "slug" to a URL.
 *
 * @param slug String
 * @param scheme String Defaults to 'https'.
 * @return url String
 */
@NonCPS
def String githubSlugToUrl(String slug, String scheme = 'https') {
  switch (scheme) {
    case 'https':
      return "https://github.com/${slug}"
      break
    case 'ssh':
      return "ssh://git@github.com/${slug}.git"
      break
    default:
      throw new Error("unknown scheme: ${scheme}")
  }
}

/*
 * Generate a github "raw" download URL.
 *
 * @param p.slug String
 * @param p.path String
 * @param p.ref String Defaults to 'master'
 * @return url String
 */
def String githubRawUrl(Map p) {
  requireMapKeys(p, [
    'slug',
    'path',
  ])
  def useP = [
    ref: 'master',
  ] + p

  def baseUrl = 'https://raw.githubusercontent.com'
  return "${baseUrl}/${useP.slug}/${useP.ref}/${useP.path}"
}

/*
 * Generate URL to versiondb manifest file.
 *
 * @param manifestId String
 * @return url String
 */
def String versiondbManifestUrl(String manifestId) {
  def scipipe = scipipeConfig()
  return githubRawUrl(
    slug: scipipe.versiondb.github_repo,
    path: "manifests/${manifestId}.txt",
  )
}

/*
 * Generate URL to repos.yaml.
 *
 * @return url String
 */
def String reposUrl() {
  def scipipe = scipipeConfig()
  return githubRawUrl(
    slug: scipipe.repos.github_repo,
    ref: scipipe.repos.git_ref,
    path: 'etc/repos.yaml',
  )
}

/*
 * Generate URL to newinstall.sh
 *
 * @return url String
 */
def String newinstallUrl() {
  def scipipe = scipipeConfig()
  return githubRawUrl(
    slug: scipipe.newinstall.github_repo,
    ref: scipipe.newinstall.git_ref,
    path: 'scripts/newinstall.sh',
  )
}

/*
 * Generate URL to shebangtron
 *
 * @return url String
 */
def String shebangtronUrl() {
  def scipipe = scipipeConfig()
  return githubRawUrl(
    slug: scipipe.shebangtron.github_repo,
    ref: scipipe.shebangtron.git_ref,
    path: 'shebangtron',
  )
}

/*
 * Sanitize string for use as docker tag
 *
 * @param tag String
 * @return tag String
 */
@NonCPS
def String sanitizeDockerTag(String tag) {
  // is there a canonical reference for the tag format?
  // convert / to -
  tag.tr('/', '_')
}

/**
 * Derive a "slug" string from a lsstsw build configuration Map.
 *
 * @param lsstswConfig Map
 * @return slug String
 */
@NonCPS
def String lsstswConfigSlug(Map lsstswConfig) {
  def lc = lsstswConfig
  def displayName = lc.display_name ?: lc.label
  def displayCompiler = lc.display_compiler ?: lc.compiler

  "${displayName}.${displayCompiler}.py${lc.python}"
}

/*
 * Sanitize string for use as an eups tag
 *
 * @param tag String
 * @return tag String
 */
@NonCPS
def String sanitizeEupsTag(String tag) {
  // eups doesn't like dots in tags, convert to underscores
  // by policy, we're not allowing dash either
  tag = tag.tr('.-', '_')
  // if the git tag is an official version, starts with a number
  // but eups tag need still to have 'v' in front
  char c = tag.charAt(0)
  if ( c.isDigit() ) {
    tag = "v" + tag
  } 
}

/*
 * Get scipipe config
 *
 * @return config Object
 */
def Object scipipeConfig() {
  readYamlFile('etc/scipipe/build_matrix.yaml')
}

/*
 * Get sqre config
 *
 * @return config Object
 */
def Object sqreConfig() {
  readYamlFile('etc/sqre/config.yaml')
}

/*
 * Get ap_verify config
 *
 * @return config Object
 */
def Object apVerifyConfig() {
  readYamlFile('etc/scipipe/ap_verify.yaml')
}

/*
 * Get sims config
 *
 * @return config Object
 */
def Object simsConfig() {
  readYamlFile('etc/sims/config.yaml')
}

/*
 * Get validate_drp config
 *
 * @return config Object
 */
def Object validateDrpConfig() {
  readYamlFile('etc/scipipe/validate_drp.yaml')
}

/*
 * Get default awscli docker image string
 *
 * @return awscliImage String
 */
def String defaultAwscliImage() {
  def dockerRegistry = sqreConfig().awscli.docker_registry
  "${dockerRegistry.repo}:${dockerRegistry.tag}"
}

/*
 * Get default codekit docker image string
 *
 * @return codekitImage String
 */
def String defaultCodekitImage() {
  def dockerRegistry = sqreConfig().codekit.docker_registry
  "${dockerRegistry.repo}:${dockerRegistry.tag}"
}

/**
 * run `release/docker/build-stack` job and parse result
 *
 * @param p.job Name of job to trigger. Defaults to
 *        `release/docker/build-stack`.
 * @param p.parameters.PRODUCTS String. Required.
 * @param p.parameters.EUPS_TAG String. Required.
 * @param p.parameters.MANIFEST_ID String. Required.
 * @param p.parameters.LSST_COMPILER String. Required.
 * @param p.parameters.NO_PUSH Boolean. Defaults to `false`.
 * @param p.parameters.TIMEOUT String. Defaults to `1'`.
 * @return json Object
 */
def Object runBuildStack(Map p) {
  // validate p Map
  requireMapKeys(p, [
    'parameters',
  ])
  p = [
    job: 'release/docker/build-stack',
  ] + p

  // validate p.parameters Map
  requireMapKeys(p.parameters, [
    'PRODUCTS',
    'EUPS_TAG',
    // not required by the triggered job but as policy by this method.
    'MANIFEST_ID',
    'LSST_COMPILER',
  ])
  p.parameters = [
    NO_PUSH: false,
    TIMEOUT: '1', // should be String
    DOCKER_TAGS: '',  // null is not a valid value for a string param
  ] + p.parameters

  def result = build(
    job: p.job,
    parameters: [
      string(name: 'PRODUCTS', value: p.parameters.PRODUCTS),
      string(name: 'EUPS_TAG', value: p.parameters.EUPS_TAG),
      booleanParam(name: 'NO_PUSH', value: p.parameters.NO_PUSH),
      string(name: 'TIMEOUT', value: p.parameters.TIMEOUT),
      string(name: 'DOCKER_TAGS', value: p.parameters.DOCKER_TAGS),
      string(name: 'MANIFEST_ID', value: p.parameters.MANIFEST_ID),
      string(name: 'LSST_COMPILER', value: p.parameters.LSST_COMPILER),
    ],
    wait: true
  )

  nodeTiny {
    resultsArtifact = 'results.json'

    step([
      $class: 'CopyArtifact',
      projectName: p.job,
      filter: resultsArtifact,
      selector: [
        $class: 'SpecificBuildSelector',
        buildNumber: result.id,
      ],
    ])

    def json = readJSON(file: resultsArtifact)
    echo "parsed ${resultsArtifact}: ${json}"
    return json
  } // nodeTiny
} // runBuildStack

/**
 * Sleep to ensure s3 objects have sync'd with the EUPS_PKGROOT.
 *
 * Example:
 *
 *     util.waitForS3()
 */
def void waitForS3() {
  def scipipe = scipipeConfig()

  stage('wait for s3 sync') {
    sleep(time: scipipe.release.s3_wait_time, unit: 'MINUTES')
  }
} // waitForS3

/**
 * Invoke block conda mirror env vars.
 *
 * Example:
 *
 *     util.withCondaMirrorEnv {
 *       util.bash './dostuff.sh'
 *     }
 *
 * @param run Closure Invoked inside of wrapper container
 */
def void withCondaMirrorEnv(Closure run) {
  // these "credentials" aren't secrets -- just a convient way of setting
  // globals for the instance. Thus, they don't need to be tightly scoped to a
  // single sh step
  withCredentials([[
    $class: 'StringBinding',
    credentialsId: 'cmirror-s3-bucket',
    variable: 'CMIRROR_S3_BUCKET'
  ]]) {
    withEnv([
      "LSST_CONDA_CHANNELS=http://${CMIRROR_S3_BUCKET}/pkgs/main http://${CMIRROR_S3_BUCKET}/pkgs/free",
      "LSST_MINICONDA_BASE_URL=http://${CMIRROR_S3_BUCKET}/miniconda",
    ]) {
      run()
    }
  } // withCredentials
} // withCondaMirrorEnv

/**
 * Create/update a clone of an lfs enabled git repo.
 *
 * Example:
 *
 *     util.checkoutLFS(
 *       githubSlug: 'foo/bar',
 *       gitRef: 'master',
 *     )
 *
 * @param p Map
 * @param p.gitRepo String github repo slug
 * @param p.gitRef String git ref to checkout. Defaults to `master`
 */
def void checkoutLFS(Map p) {
  requireMapKeys(p, [
    'githubSlug',
    'gitRef',
  ])
  p = [
    gitRef: 'master',
  ] + p

  def gitRepo = githubSlugToUrl(p.githubSlug)

  def lfsImage = 'lsstsqre/gitlfs'

  // running a git clone in a docker.inside block is broken
  git([
    url: gitRepo,
    branch: p.gitRef,
    changelog: false,
    poll: false
  ])

  try {
    insideDockerWrap(
      image: lfsImage,
      pull: true,
    ) {
      bash('git lfs pull origin')
    }
  } finally {
    // try not to break jenkins clone mangement
    bash 'rm -f .git/hooks/post-checkout'
  }
} // checkoutLFS

/**
 * Download URL resource and write it to disk.
 *
 * Example:
 *
 *     util.downloadFile(
 *       url: 'https://example.org/foo/bar.baz',
 *       destFile: 'foo/bar.baz',
 *     )
 *
 * @param p Map
 * @param p.url String URL to fetch
 * @param p.destFile String path to write downloaded file
 */
def void downloadFile(Map p) {
  requireMapKeys(p, [
    'url',
    'destFile',
  ])

  writeFile(file: p.destFile, text: new URL(p.url).getText())
}

/**
 * Download `manifest.txt` from `lsst/versiondb`.
 *
 * Example:
 *
 *     util.downloadManifest(
 *       destFile: 'foo/manifest.txt',
 *       manifestId: 'b1234',
 *     )
 *
 * @param p Map
 * @param p.destFile String path to write downloaded file
 * @param p.manifestId String manifest build id aka bNNNN
 */
def void downloadManifest(Map p) {
  requireMapKeys(p, [
    'destFile',
    'manifestId',
  ])

  def manifestUrl = versiondbManifestUrl(p.manifestId)
  downloadFile(
    url: manifestUrl,
    destFile: p.destFile,
  )
}

/**
 * Download a copy of `repos.yaml`
 *
 * Example:
 *
 *     util.downloadRepos(
 *       destFile: 'foo/repos.yaml',
 *     )
 *
 * @param p Map
 * @param p.destFile String path to write downloaded file
 */
def void downloadRepos(Map p) {
  requireMapKeys(p, [
    'destFile',
  ])

  def reposUrl = reposUrl()
  downloadFile(
    url: reposUrl,
    destFile: p.destFile,
  )
}

/**
 * Collect artifacts
 *
 * Example:
 *
 *     // note: the whitespace is needed to prevent the example from exiting
 *     // the comment block -- not needed in real code
 *     util.record([
 *       "${runDir}/** /*.log",
 *       "${runDir}/** /*.json",
 *     ])
 *
 * @param archiveDirs List paths to be collected.
 */
def void record(List archiveDirs) {
  archiveDirs = relPath(pwd(), archiveDirs)

  archiveArtifacts([
    artifacts: archiveDirs.join(', '),
    excludes: '**/*.dummy',
    allowEmptyArchive: true,
    fingerprint: true
  ])
} // record

/**
 * Relativize a list of paths.
 *
 * Example:
 *
 *     util.relPath(pwd(), [
 *       "/foo/bar/baz/bonk",
 *       "/foo/bar/baz/quix",
 *     ])
 *
 * @param relativeToDir String base path
 * @param path List paths to be relativized
 * @return List of relativized paths
 */
def List relPath(String relativeToDir, List paths) {
  // convert to relative paths
  // https://gist.github.com/ysb33r/5804364
  def rootDir = new File(relativeToDir)
  return paths.collect { it ->
    // skip non-rel paths
    if (!it.startsWith('/')) { return it }
    def fullPath = new File(it)
    rootDir.toPath().relativize(fullPath.toPath()).toFile().toString()
  }
} // relPath

/**
 * Relativize a list of paths.
 *
 * Example:
 *
 *     util.xz([
 *       '** /*.foo',
 *       '** /*.bar',
 *     ])
 *
 * @param patterns List of file patterns to compress
 * @return List of compressed files
 */
def List xz(List patterns) {
  patterns = relPath(pwd(), patterns)
  def files = patterns.collect { g -> findFiles(glob: g) }.flatten()
  def targetFile = 'compress_files.txt'
  writeFile(file: targetFile, text: files.join("\n") + "\n")

  // compressing an example hsc output file
  // (cmd)       (ratio)  (time)
  // xz -T0      0.183    0:20
  // xz -T0 -9   0.180    1:23
  // xz -T0 -9e  0.179    1:28

  // compress but do not remove original file
  util.bash "xz -T0 -9ev --keep --files=${targetFile}"
  return files.collect { f -> "${f}.xz" }
}

/**
 * Collect junit reports
 *
 * Example:
 *
 *     // note: the whitespace is needed to prevent the example from exiting
 *     // the comment block -- not needed in real code
 *     util.junit([
 *       "${runDir}/** /pytest-*.xml",
 *     ])
 *
 * @param testResults List paths to be collected.
 */
def void junit(List testResults) {
  testResults = relPath(pwd(), testResults)

  junit([
    testResults: testResults.join(', '),
    allowEmptyResults: true,
  ])
} // junit

/**
 * push results to squash using dispatch-verify.
 *
 * Example:
 *
 *     util.runDispatchVerify(
 *       runDir: runDir,
 *       lsstswDir: lsstswDir,
 *       datasetName: datasetName,
 *       resultFile: resultFile,
 *     )
 *
 * @param p Map
 * @param p.runDir String
 * @param p.lsstswDir String Path to (the fake) lsstsw dir
 * @param p.datasetName String The dataset name. Eg., validation_data_cfht
 * @param p.resultFile String [JSON] file to push to squash.
 */
def void runDispatchVerify(Map p) {
  util.requireMapKeys(p, [
    'runDir',
    'lsstswDir',
    'datasetName',
    'resultFile',
  ])

  def sqre = sqreConfig()

  def run = {
    util.bash '''
      set +o xtrace
      source /opt/lsst/software/stack/loadLSST.bash
      setup verify
      set -o xtrace

      dispatch_verify.py \
        --env jenkins \
        --lsstsw "$LSSTSW_DIR" \
        --url "$SQUASH_URL" \
        --user "$SQUASH_USER" \
        --password "$SQUASH_PASS" \
        "$RESULT_FILE"
    '''
  } // run

  /*
  These are already present under pipeline:
  - BUILD_ID
  - BUILD_URL

  This var was defined automagically by matrixJob and now must be manually
  set:
  - dataset
  */
  withEnv([
    "LSSTSW_DIR=${p.lsstswDir}",
    "dataset=${p.datasetName}",
    "SQUASH_URL=${sqre.squash.url}",
    "RESULT_FILE=${p.resultFile}",
  ]) {
    withCredentials([[
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'squash-api-user',
      usernameVariable: 'SQUASH_USER',
      passwordVariable: 'SQUASH_PASS',
    ]]) {
      dir(p.runDir) {
        run()
      }
    } // withCredentials
  } // withEnv
} // runDispatchVerify

/**
 * Create a "fake" lsstsw-ish dir structure as expected by
 * `dispatch-verify.py`, which includes a `manifest.txt` and a copy of
 * `repos.yaml`.
 *
 * Example:
 *
 *     util.createFakeLsstswClone(
 *       fakeLsstswDir: fakeLsstswDir,
 *       manifestId: manifestId,
 *     )
 *
 * @param p Map
 * @param p.fakeLsstswDir String dir path
 * @param p.manifestId String versiondb manifest id
 */
def void createFakeLsstswClone(Map p) {
  requireMapKeys(p, [
    'fakeLsstswDir',
    'manifestId',
  ])

  def fakeLsstswDir    = p.fakeLsstswDir
  def fakeManifestDir  = "${fakeLsstswDir}/build"
  def fakeManifestFile = "${fakeManifestDir}/manifest.txt"
  def fakeReposDir     = "${fakeLsstswDir}/etc"
  def fakeReposFile    = "${fakeReposDir}/repos.yaml"

  emptyDirs([
    fakeManifestDir,
    fakeReposDir,
  ])

  downloadManifest(
    destFile: fakeManifestFile,
    manifestId: p.manifestId,
  )
  downloadRepos(destFile: fakeReposFile)
} // createFlakeLsstwClone


return this;
