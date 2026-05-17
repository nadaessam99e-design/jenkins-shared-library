def call(Map config) {
    def serviceName = config.serviceName
    def appPort = config.appPort
    def dockerRepo = "shamsmo0h/${serviceName}"
    def gitopsPath = config.gitopsPath
    def imageTag = config.imageTag ?: "${env.BUILD_NUMBER}"
    def dockerImage = "${dockerRepo}:${imageTag}"

    pipeline {
        agent any

        tools {
            maven 'mvn'
            jdk 'jdk17'
        }

        environment {
            DOCKER_IMAGE = "${dockerImage}"
            DOCKER_REPO = "${dockerRepo}"
            SERVICE_NAME = "${serviceName}"
            APP_PORT = "${appPort}"
            IMAGE_TAG = "${imageTag}"
        }

        stages {
            stage('1. Checkout Source Code') {
                steps {
                    checkout scm
                }
            }

            stage('2. Configure Application Properties') {
                steps {
                    script {
                        sh """
                            if [ -f src/main/resources/application.properties ]; then
                                echo "server.port=${APP_PORT}" >> src/main/resources/application.properties
                                echo "Application port set to ${APP_PORT}"
                            else
                                echo "No application.properties found, creating one"
                                mkdir -p src/main/resources
                                echo "server.port=${APP_PORT}" > src/main/resources/application.properties
                            fi
                        """
                    }
                }
            }

            stage('3. Compile with Maven') {
                steps {
                    sh 'mvn clean compile'
                }
            }

            stage('4. Run Tests') {
                steps {
                    sh 'mvn test'
                }
            }

            stage('5. Package JAR') {
                steps {
                    sh 'mvn package -DskipTests'
                }
            }

            stage('6. Build Docker Image') {
                steps {
                    script {
                        sh """
                            docker build -t ${DOCKER_IMAGE} .
                            docker tag ${DOCKER_IMAGE} ${DOCKER_REPO}:latest
                        """
                    }
                }
            }

            stage('7. Push to Docker Hub') {
                steps {
                    withCredentials([string(credentialsId: 'dockerhub-creds', variable: 'DOCKER_PASSWORD')]) {
                        sh """
                            echo ${DOCKER_PASSWORD} | docker login -u shamsmo0h --password-stdin
                            docker push ${DOCKER_IMAGE}
                            docker push ${DOCKER_REPO}:latest
                        """
                    }
                }
            }

           stage('8. Update GitOps Repository') {
    steps {
        script {
            withCredentials([usernamePassword(credentialsId: 'github-creds', 
                                              usernameVariable: 'GIT_USERNAME', 
                                              passwordVariable: 'GIT_PASSWORD')]) {
                dir('gitops-temp') {
                    sh """
                        git clone https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/shamsmo0/petclinic-gitops.git .
                        git config user.email "jenkins@petclinic.com"
                        git config user.name "Jenkins CI"
                        sed -i 's|image: shamsmo0h/${SERVICE_NAME}:.*|image: ${DOCKER_REPO}:${IMAGE_TAG}|' ${gitopsPath}
                        git add ${gitopsPath}
                        git commit -m "Update ${SERVICE_NAME} to ${IMAGE_TAG} [skip ci]" || echo "No changes"
                        git push origin main
                    """
                }
            }
        }
    }
}
            stage('9. Trigger ArgoCD Sync') {
                steps {
                    script {
                        echo "ArgoCD will auto-sync within 3 minutes"
                        echo "Or run: argocd app sync ${SERVICE_NAME}"
                    }
                }
            }
        }

        post {
            always {
                cleanWs()
            }
            success {
                echo "Pipeline SUCCESS for ${SERVICE_NAME}"
                echo "Image: ${DOCKER_IMAGE}"
                echo "GitOps updated: ${gitopsPath}"
            }
            failure {
                echo "Pipeline FAILED for ${SERVICE_NAME}"
            }
        }
    }
}
