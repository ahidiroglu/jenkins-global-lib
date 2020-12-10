#!/usr/bin/env groovy
package com.lib
import groovy.json.JsonSlurper
import hudson.FilePath


def runPipeline() {
  def google_project_id = ""
  def commonFunctions = new CommonFunction()
  def triggerUser = commonFunctions.getBuildUser()
  def branch = "${scm.branches[0].name}".replaceAll(/^\*\//, '')
  def k8slabel = "jenkins-pipeline-${UUID.randomUUID().toString()}"
  def timeStamp = Calendar.getInstance().getTime().format('ssmmhh-ddMMYYY',TimeZone.getTimeZone('CST'))

  // Generating the repository name 
  def repositoryName = "${JOB_NAME}"
      .split('/')[0]
      .replace('-fuchicorp', '')
      .replace('-build', '')
      .replace('-deploy', '')

  try {

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
        - name: packer
          image: hashicorp/packer:latest
          imagePullPolicy: IfNotPresent
          command:
          - cat
          tty: true
          volumeMounts:
            - mountPath: /etc/secrets/service-account/
              name: google-service-account
        serviceAccountName: default
        securityContext:
          runAsUser: 0
          fsGroup: 0
        volumes:
          - name: google-service-account
            secret:
              secretName: google-service-account
    """
    node('master') {
      // Getting the base domain name from Jenkins master < example: fuchicorp.com >
      google_project_id = sh(returnStdout: true, script: 'echo $GOOGLE_PROJECT_ID').trim()
  }

    podTemplate(name: k8slabel, label: k8slabel, yaml: slavePodTemplate) {
      node(k8slabel) {
        timestamps {
          container('packer') {
              stage("Pulling the code") {
                checkout scm
              }
            
          dir('fuchicorp-bastion/packer-scripts/') {
            withEnv([
                     "GOOGLE_PROJECT_ID=${google_project_id}", 
                     "GOOGLE_APPLICATION_CREDENTIALS=/etc/secrets/service-account/credentials.json"]) {

              stage("Packer Validate") {
                println('Validating the syntax.')
                sh 'packer validate -syntax-only script.json'

                println('Validating the packer code.')
                sh 'packer validate script.json'
              }

              stage("Packer Build") {
                println('Building the packer.')
                sh 'packer build script.json'
              }
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
