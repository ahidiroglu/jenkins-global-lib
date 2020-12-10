#!/usr/bin/env groovy
package com.lib
import groovy.json.JsonSlurper
import static groovy.json.JsonOutput.*
import hudson.FilePath



def runPipeline() {
  def common_docker   = new JenkinsDeployerPipeline()
  def commonFunctions = new CommonFunction()
  def notifier        = new JenkinsNotificator()
  def triggerUser     = commonFunctions.getBuildUser()
  def branch          = "${scm.branches[0].name}".replaceAll(/^\*\//, '').replace("/", "-").toLowerCase()
  def gitUrl          = "${scm.getUserRemoteConfigs()[0].getUrl()}"
  def k8slabel        = "jenkins-pipeline-${UUID.randomUUID().toString()}"
  def deploymentName = "${JOB_NAME}".split('/')[0].replace('-fuchicorp', '').replace('-build', '').replace('-deploy', '')

  try {


    // Trying to build the job
    properties([ parameters([

      // Boolean Paramater for terraform apply or not 
      booleanParam(defaultValue: false, 
      description: 'Apply All Changes', 
      name: 'terraform_apply'),

      // Boolean Paramater for terraform destroy 
      booleanParam(defaultValue: false, 
      description: 'Destroy deployment', 
      name: 'terraform_destroy'),
      
      // Branch name to deploy environment 
      gitParameter(branch: '', branchFilter: 'origin/(.*)', defaultValue: 'origin/master', 
      description: 'Please select the branch name to deploy', name: 'branchName', 
      quickFilterEnabled: true, selectedValue: 'NONE', sortMode: 'NONE', tagFilter: '*', type: 'PT_BRANCH'),

      // Extra configurations to deploy with it 
      text(name: 'deployment_tfvars', 
      defaultValue: 'extra_values = "tools"', 
      description: 'terraform configuration'),

      // Init commands to run before terraform apply 
      text(name: 'init_commands', 
      defaultValue: 'helm ls', 
      description: 'Please add commands you like to run before terraform apply'),

      // Boolean Paramater for debuging this job 
      booleanParam(defaultValue: false, 
      description: 'If you would like to turn on debuging click this!!', 
      name: 'debugMode')

      ])
      ])


      if (triggerUser != "AutoTrigger") {
        commonFunctions.validateDeployment(triggerUser, 'prod')
        
      } else {
        println("The job is triggereted automatically and skiping the validation !!!")
      }

      // Jenkins slave to build this job 
      def slavePodTemplate = """
      metadata:
        labels:
          k8s-label: ${k8slabel}
        annotations:
          jenkinsjoblabel: ${env.JOB_NAME}-${env.BUILD_NUMBER}
      spec:
        affinity:
          podAntiAffinity:
            requiredDuringSchedulingIgnoredDuringExecution:
            - labelSelector:
                matchExpressions:
                - key: component
                  operator: In
                  values:
                  - jenkins-jenkins-master
              topologyKey: "kubernetes.io/hostname"
        containers:
        - name: fuchicorptools
          image: fuchicorp/buildtools
          imagePullPolicy: Always
          command:
          - cat
          tty: true
          volumeMounts:
            - mountPath: /var/run/docker.sock
              name: docker-sock
            - mountPath: /etc/secrets/service-account/
              name: google-service-account
        serviceAccountName: common-service-account
        securityContext:
          runAsUser: 0
          fsGroup: 0
        volumes:
          - name: google-service-account
            secret:
              secretName: google-service-account
          - name: docker-sock
            hostPath:
              path: /var/run/docker.sock
    """

    podTemplate(name: k8slabel, label: k8slabel, yaml: slavePodTemplate, showRawYaml: params.debugMode) {
      node(k8slabel) {

        stage("Deployment Info") {

          // Colecting information to show on stage <Deployment Info>
          println(prettyPrint(toJson([
            "Deployment" : deploymentName,
            "Builder" : triggerUser,
            "Build": env.BUILD_NUMBER
          ])))
        }

        container('fuchicorptools') {

          stage("Polling SCM") {
            checkout([$class: 'GitSCM', 
                       branches: [[name: branchName]], 
                       doGenerateSubmoduleConfigurations: false, 
                       extensions: [], submoduleCfg: [], 
                       userRemoteConfigs: [[credentialsId: 'github-access-token', url: gitUrl]]])
          }

          stage('Generate Configurations') {
            sh  """
                cat  /etc/secrets/service-account/credentials.json > fuchicorp-service-account.json
                ## This script should move to docker container to set up ~/.kube/config
                sh /scripts/Dockerfile/set-config.sh
                """

            deployment_tfvars += """
            ## credentials            = \"fuchicorp-service-account.json\"
            """.stripIndent()

            writeFile( [file: "common_tools.tfvars", text: "${deployment_tfvars}"] )

            if (params.debugMode) {
              sh """
                echo #############################################################
                cat common_tools.tfvars
                echo #############################################################
              """
            }
            
            try {
                withCredentials([
                    file(credentialsId: "${deploymentName}-config", variable: 'default_config')
                ]) {
                    sh """
                      #!/bin/bash
                      cat \$default_config >> common_tools.tfvars
                      
                    """
                    if (params.debugMode) {
                      sh """
                        echo #############################################################
                        cat common_tools.tfvars
                        echo #############################################################
                      """
                    }
                }
            
                println("Found default configurations appanded to main configuration")
            } catch (e) {
                println("Default configurations not founds. Skiping!!")
            }
          }
          
          stage('Terraform Apply/Plan') {
            if (!params.terraform_destroy) {
              if (params.terraform_apply) {
                notifier.sendMessage('slack', 'APPLIED', 'notifications', null)

                dir("${WORKSPACE}") {
                  echo "##### Terraform Applying the Changes ####"
                  sh """#!/bin/bash -e
                    ${params.init_commands}
                    echo 'init script successfully finished'
                    source set-env.sh common_tools.tfvars
                    terraform apply --auto-approve -var-file=common_tools.tfvars
                    """
                }

              } else { 

                dir("${WORKSPACE}") {
                  echo "##### Terraform Plan (Check) the Changes #### "
                  sh """#!/bin/bash -e
                    ${params.init_commands}
                    echo 'init script successfully finished'
                    source set-env.sh common_tools.tfvars
                    terraform plan -var-file=common_tools.tfvars
                  """
                }
              }
            }
          }

          stage('Terraform Destroy') {
            if (!params.terraform_apply) {
              if (params.terraform_destroy) {
                notifier.sendMessage('slack', 'DESTROYED', 'notifications', null)
                dir("${WORKSPACE}") {
                    echo "##### Terraform Destroying ####"
                    sh """#!/bin/bash -e
                        ${params.init_commands}
                        echo 'init script successfully finished'
                        # source set-env.sh common_tools.tfvars
                        # terraform destroy --auto-approve -var-file=common_tools.tfvars
                    """
                    currentBuild.result = 'ABORTED'
                    error('Terraform destroy is disabled for now. Please run all destroy from command line.')
                }
              }
          }

            if (params.terraform_destroy) {
              if (params.terraform_apply) {
                println(""" Sorry you can not destroy and apply at the same time """)
                currentBuild.result = 'FAILURE'
              }
            }
          }
        }
      } 
    }
  } catch (e) {
    currentBuild.result = 'FAILURE'
    println("ERROR Detected:")
    println(e.getMessage())
    }
}
return this

