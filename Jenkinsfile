pipeline {
  agent {
    docker {
      image 'velocitypowered/openjdk8-plus-git:slim'
      args '-v gradle-cache:/root/.gradle:rw -v maven-repo:/maven-repo:rw -v javadoc:/javadoc'
    }

  }
  stages {
    stage('Build') {
      steps {
        sh './gradlew shadowJar'
        archiveArtifacts 'proxy/build/libs/*-all.jar,api/build/libs/*-all.jar'
      }
    }
    stage('Test') {
      steps {
        sh './gradlew test'
      }
    }
    stage('Deploy Artifacts') {
      steps {
        sh 'export MAVEN_DEPLOYMENT=true; ./gradlew publish'
      }
    }
    stage('Deploy Javadoc') {
      steps {
        sh 'rsync -av --delete ./api/build/docs/javadoc/ /javadoc'
      }
    }
  }
}