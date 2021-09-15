import groovy.json.JsonSlurper

def curlCMD = "curl -sSL https://raw.github.com/redhat-developer/codeready-workspaces/crw-2-rhel-8/dependencies/job-config.json".execute().text

def jsonSlurper = new JsonSlurper();
def config = jsonSlurper.parseText(curlCMD);

def JOB_BRANCHES = ["2.11", "2.x"]
for (JB in JOB_BRANCHES) {
    JOB_BRANCH=""+JB
    MIDSTM_BRANCH="crw-" + JOB_BRANCH.replaceAll(".x","") + "-rhel-8"
    jobPath="${FOLDER_PATH}/${ITEM_NAME}_" + JOB_BRANCH
    pipelineJob(jobPath){
        disabled(config.Jobs.theia[JB].disabled) // on reload of job, disable to avoid churn
        UPSTM_NAME="che-theia"
        MIDSTM_NAME="theia" // do we need three midstreams here? or three jobs?
        SOURCE_REPO="eclipse-che/" + UPSTM_NAME
        MIDSTM_REPO="redhat-developer/codeready-workspaces-images"
        NODE_VERSION="" + config.Other.NODE_VERSION[JB]
        YARN_VERSION="" + config.Other.YARN_VERSION[JB]

        def cmd = "git ls-remote --heads https://github.com/" + SOURCE_REPO + ".git " + config.Jobs.theia[JB].upstream_branch[0]
        def BRANCH_CHECK=cmd.execute().text

        SOURCE_BRANCH=""+config.Jobs.theia[JB].upstream_branch[0];
        if (!BRANCH_CHECK) {
            SOURCE_BRANCH=""+config.Jobs.theia[JB].upstream_branch[1]
        }

        description('''
<ul>
<li>Upstream: <a href=https://github.com/''' + SOURCE_REPO + '''>''' + UPSTM_NAME + '''</a></li>

<li>Midstream: <ul>
    <li><a href=https://github.com/''' + MIDSTM_REPO + '''/tree/''' + MIDSTM_BRANCH + '''/codeready-workspaces-''' + MIDSTM_NAME + '''-dev/>crw-''' + MIDSTM_NAME + '''-dev</a></li>
    <li><a href=https://github.com/''' + MIDSTM_REPO + '''/tree/''' + MIDSTM_BRANCH + '''/codeready-workspaces-''' + MIDSTM_NAME + '''/>crw-''' + MIDSTM_NAME + '''</a></li>
    <li><a href=https://github.com/''' + MIDSTM_REPO + '''/tree/''' + MIDSTM_BRANCH + '''/codeready-workspaces-''' + MIDSTM_NAME + '''-endpoint/>crw-''' + MIDSTM_NAME + '''-endpoint</a></li>
</ul></li>

<li>Downstream: <ul>
    <li><a href=http://pkgs.devel.redhat.com/cgit/containers/codeready-workspaces-''' + MIDSTM_NAME + '''-dev?h=''' + MIDSTM_BRANCH + '''>''' + MIDSTM_NAME + '''-dev</a></li>
    <li><a href=http://pkgs.devel.redhat.com/cgit/containers/codeready-workspaces-''' + MIDSTM_NAME + '''?h=''' + MIDSTM_BRANCH + '''>''' + MIDSTM_NAME + '''</a></li>
    <li><a href=http://pkgs.devel.redhat.com/cgit/containers/codeready-workspaces-''' + MIDSTM_NAME + '''-endpoint?h=''' + MIDSTM_BRANCH + '''>''' + MIDSTM_NAME + '''-endpoint</a></li>
</ul></li>
</ul>

<p>
1. <a href=../crw-theia-sources_''' + JOB_BRANCH + '''>crw-theia-sources_''' + JOB_BRANCH + '''</a>: Bootstrap CRW Theia components by building temporary containers and pushing them to quay, then trigger <a href=../sync-to-downstream_''' + JOB_BRANCH + '''/>sync-to-downstream_''' + JOB_BRANCH + '''</a> and <a href=../get-sources-rhpkg-container-build_''' + JOB_BRANCH + '''/>get-sources-rhpkg-container-build</a>.<br/>
2. <a href=../crw-theia-akamai_''' + JOB_BRANCH + '''>crw-theia-akamai_''' + JOB_BRANCH + '''</a>: Push Theia artifacts to akamai CDN <br/>

<p>
Results:
<ul>
<li><a href=https://quay.io/crw/theia-dev-rhel8>quay.io/crw/theia-dev-rhel8</a></li>
<li><a href=https://quay.io/crw/theia-rhel8>quay.io/crw/theia-rhel8</a></li>
<li><a href=https://quay.io/crw/theia-endpoint-rhel8>quay.io/crw/theia-endpoint-rhel8</a></li>
</ul>
        ''')

        properties {
            ownership {
                primaryOwnerId("nboldt")
            }

            githubProjectUrl("https://github.com/" + SOURCE_REPO)

            pipelineTriggers {
                triggers{
                    pollSCM{
                        scmpoll_spec("H H/4 * * *") // every 4hrs
                    }
                }
            }

            disableResumeJobProperty()
            disableConcurrentBuildsJobProperty()
            quietPeriod(30) // no more than one build every 30s
        }

        throttleConcurrentBuilds {
            maxPerNode(1)
            maxTotal(1)
        }

        logRotator {
            daysToKeep(5)
            numToKeep(20)
            artifactDaysToKeep(5)
            artifactNumToKeep(3)
        }

        parameters{
            stringParam("SOURCE_BRANCH", SOURCE_BRANCH)
            stringParam("MIDSTM_BRANCH", MIDSTM_BRANCH)
            stringParam("nodeVersion", NODE_VERSION, "Leave blank if not needed")
            stringParam("yarnVersion", YARN_VERSION, "Leave blank if not needed")
            // TODO CRW-1609 implement tag deletion option
            // booleanParam("cleanTmpImages", false, "If true, delete tmp images from quay before starting build(s)")
            // @since 2.9 - sync-to-downstream expects comma separated values
            textParam("CONTAINERS", '''codeready-workspaces-theia-dev, codeready-workspaces-theia, codeready-workspaces-theia-endpoint''', '''comma-separated list of containers to build, in order<br/>
* include one, some, or all as needed<br/>
* default: codeready-workspaces-theia-dev, codeready-workspaces-theia, codeready-workspaces-theia-endpoint''')
            stringParam("PLATFORMS", "x86_64, s390x, ppc64le", '''comma-separated list of architectures on which to build containers<br/>
* include one, some, or all as needed<br/>
* default: x86_64, s390x, ppc64le''')
            booleanParam("FORCE_BUILD", false, "If true, trigger a rebuild even if no changes were pushed to pkgs.devel")
        }

        // Trigger builds remotely (e.g., from scripts), using Authentication Token = CI_BUILD
        authenticationToken('CI_BUILD')

        definition {
            cps{
                sandbox(true)
                script(readFileFromWorkspace('jobs/CRW_CI/crw-theia-sources_'+JOB_BRANCH+'.jenkinsfile'))
            }
        }
    }
}