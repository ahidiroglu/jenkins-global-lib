#!/usr/bin/env groovy
package com.lib
import groovy.json.JsonSlurper

def isAdmin(username) {
    def instance = Jenkins.getInstance()
    return instance.getAuthorizationStrategy().getACL(User.get(username))
    .hasPermission(User.get(username).impersonate(), hudson.model.Hudson.ADMINISTER)
}


def scheduleBaseJobs(String baseName, String jobName) {
  /* If Job name contains 'base' and branch name is master or develop
  * scheduleBaseJobs schedule the job to run from 1 through 6
  */

  if (baseName.contains('base'))  {
    if (jobName == 'master' || jobName == 'develop') {
      properties([[$class: 'RebuildSettings',
      autoRebuild: false,
      rebuildDisabled: false],
      // “At minute 0 past every hour from 1 through 6.”
      pipelineTriggers([cron('0 1-6 * * *')])])
    }
  }
}


def validateDeployment(username, environment) {

    if (isAdmin(username)) {
        println("You are allowed to do prod deployments!!")

    } else {

        if (environment in ['dev', 'qa', 'test', 'stage']) {
            println("You are allowed to do non-prod deployments!!")

        } else {
             currentBuild.result = 'ABORTED' 	      
             error('You are not allowed to do prod deployments!!')
        }
    }
}


// Function to get user id 
@NonCPS
def getBuildUser() {
      try {
        return currentBuild.rawBuild.getCause(Cause.UserIdCause).getUserId()
      } catch (e) {
        def user = "AutoTrigger"
        return user
      }
  }

// Create SonarQube Project 
def createProject(projectKey, projectName, url) {
    def jsonSlurper = new JsonSlurper()
    def data = new URL("${url}/api/projects/create?project=${projectKey}&name=${projectName}").openConnection();
        data.setRequestMethod("POST")
        data.setRequestProperty("Content-Type", "application/json")
    
    
    if (data.responseCode == 200) {
      def responseData = jsonSlurper.parseText(data.getInputStream().getText())
      return responseData.project.key.toString()
    } else {
      return null
    }
}

// Generating the token to run the sonar scan
def genToken(tokenName, url, username, password) {
    def jsonSlurper = new JsonSlurper()
    def data = new URL("${url}/api/user_tokens/generate?name=${tokenName}").openConnection();
        data.setRequestProperty("Authorization", "Basic " + "${username}:${password}".bytes.encodeBase64().toString())
        data.setRequestProperty("Content-Type", "application/json")
        data.setRequestMethod("POST")
    
    if (data.responseCode == 200) {
      def responseData = jsonSlurper.parseText(data.getInputStream().getText())
      return responseData.token.toString()
    } else {
      return null
    }
}


return this 
