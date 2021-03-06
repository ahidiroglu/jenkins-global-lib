def runPipeline() {
  def k8slabel = "jenkins-pipeline-${UUID.randomUUID().toString()}"
  properties([
      [$class: 'RebuildSettings', autoRebuild: false, rebuildDisabled: false],
      parameters([
          booleanParam(defaultValue: false, description: 'Please select to apply all changes to the environment', name: 'terraform_apply'),
          booleanParam(defaultValue: false, description: 'Please select to destroy all changes to the environment', name: 'terraform_destroy'),
      ])
  ])
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
          serviceAccountName: default
          securityContext:
            runAsUser: 0
            fsGroup: 0
          volumes:
            - name: docker-sock
              hostPath:
                path: /var/run/docker.sock
      """
  podTemplate(name: k8slabel, label: k8slabel, yaml: slavePodTemplate, showRawYaml: false) {
    node(k8slabel){
      stage("Pull Repo"){
        git branch: 'master', credentialsId: 'github-common-access', url: 'https://github.com/fuchicorp/cluster-infrastructure.git'
      }
      container("fuchicorptools") {
        dir('aws/eks') {
          withCredentials([usernamePassword(credentialsId: 'aws-accessgulnaz-dev', passwordVariable: 'AWS_SECRET_ACCESS_KEY', usernameVariable: 'AWS_ACCESS_KEY_ID')]) {
              stage("Terrraform Init"){
                sh """
                  ls -la
                  source ./set-env.sh configurations/dev/us-east-1.tfvars
                """
              }
              if (terraform_apply.toBoolean()) {
                stage("Terraform Apply"){
                    println("AWS EKS deployment starting in AWS_REGION")
                    sh """
                    terraform apply -var-file configurations/dev/us-east-1.tfvars -auto-approve
                    """
                }
              }
              else if (terraform_destroy.toBoolean()) {
                stage("Terraform Destroy"){
                  println("AWS EKS to be destroyed in AWS_REGION")
                    sh """
                      terraform destroy -var-file configurations/dev/us-east-1.tfvars -auto-approve
                    """
                }
              }
              else {
                stage("Terraform Plan"){
                  sh """
                  terraform plan -var-file configurations/dev/us-east-1.tfvars
                  echo "Nothinh to do.Please choose either apply or destroy"
                  """
                }
              }
          }
        }
      }
    }
  }
}