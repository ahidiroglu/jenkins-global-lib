//There is a need to create Credentials with type "secret file" for tfvars file with ID "common-tools"
//There is a need to create Credentials with type "secret file" for ServiceAccount file with ID "common_json"

properties([[$class: 'RebuildSettings', autoRebuild: false, rebuildDisabled: false], pipelineTriggers([cron('H 1 * * *')])])

def k8slabel = "jenkins-pipeline-${UUID.randomUUID().toString()}"
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
        - name: docker
          image: docker:latest
          imagePullPolicy: Always
          command:
          - cat
          tty: true
          volumeMounts:
            - mountPath: /var/run/docker.sock
              name: docker-sock
            - mountPath: /etc/secrets/service-account/
              name: google-service-account
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
    
    podTemplate(name: k8slabel, label: k8slabel, yaml: slavePodTemplate) {
      node(k8slabel) {
        
     stage("Pull SCM") {
          git 'https://github.com/fuchicorp/common_tools.git'
        }

        stage('Generate Configurations') {
          container('fuchicorptools') {
             withCredentials([
                file(credentialsId: 'common-tools', variable: 'common_tools_conf'),
                file(credentialsId: 'common_json', variable: 'common_json')
                ]) {
            sh """
            ## This script should move to docker container to set up ~/.kube/config
            cat \$common_json &> ${WORKSPACE}/common-service-account.json
            sh /scripts/Dockerfile/set-config.sh
            ls -a /
            ls -a /root/
            ls -a ~/.kube
            """
                }
        }

        }
        stage("Apply/Plan")  {
          container('fuchicorptools') {
              withCredentials([
                file(credentialsId: 'common-tools', variable: 'common_tools_conf'),
                file(credentialsId: 'common_json', variable: 'common_json')
                ]) {
                dir("${WORKSPACE}") {
                    sh '''#!/bin/bash -e
                    cat \$common_json &> ${WORKSPACE}/common-service-account.json
                    cat \$common_tools_conf &> ${WORKSPACE}/common.tfvars
                    source set-env.sh common.tfvars 
                    terraform apply --auto-approve -var-file=common.tfvars 
                    '''
                }
              }
            
          }
        }
    }
  }