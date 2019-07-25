import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.container.Container
import com.google.api.services.container.ContainerScopes
import com.google.api.services.container.model.Cluster
import com.google.api.services.container.model.CreateClusterRequest
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials

import java.util.concurrent.TimeUnit

import static org.awaitility.Awaitility.await

@Grapes([
        @Grab(group = "com.google.auth", module = "google-auth-library-oauth2-http", version = "0.16.2"),
        @Grab(group = "com.google.api-client", module = "google-api-client", version = "1.30.1"),
        @Grab(group = "com.google.apis", module = "google-api-services-container", version = "v1-rev78-1.25.0"),
        @Grab(group = "org.awaitility", module = "awaitility-groovy", version = "3.1.6")
])
class GoogleKubernetesConfiguration {

    private final def APPLICATION_NAME = "spring-cloud-kubernetes-ci-build"
    private final def HTTP_TRANSPORT = new NetHttpTransport()
    private final def JSON_FACTORY = new JacksonFactory()
    private final def GCP_LOCATION = "europe-west2-a"
    private final def GCP_PROJECT = "cf-sandbox-ohughes"
    private final def CLUSTER_NAME = "${APPLICATION_NAME}-cluster"

    def setup() {
        def gkeClient = new Container.Builder(HTTP_TRANSPORT, JSON_FACTORY, loadCredentials())
                .setApplicationName(APPLICATION_NAME)
                .build()

        CreateClusterRequest request = buildClusterRequest()

        gkeClient.projects()
                .zones()
                .clusters()
                .create(GCP_PROJECT, GCP_LOCATION, request)
                .execute()

        await().atMost(5, TimeUnit.MINUTES)
                .pollInterval(5, TimeUnit.SECONDS)
                .until { getClusterStatus(gkeClient) == "RUNNING" }

    }

    def destroy() {
        def gkeClient = new Container.Builder(HTTP_TRANSPORT, JSON_FACTORY, loadCredentials())
                .setApplicationName(APPLICATION_NAME)
                .build()

        gkeClient.projects()
                .zones()
                .clusters()
                .delete(GCP_PROJECT, GCP_LOCATION, CLUSTER_NAME)
                .execute()

        await().atMost(5, TimeUnit.MINUTES)
                .pollInterval(5, TimeUnit.SECONDS)
                .until { getClusterStatus(gkeClient) == null }
    }

    private buildClusterRequest() {
        def request = new CreateClusterRequest()
        def cluster = new Cluster()
        cluster.setName(CLUSTER_NAME)
        cluster.setInitialNodeCount(1)
        request.setCluster(cluster)
        request
    }

    private String getClusterStatus(Container container) {
        Cluster cluster = null
        try {
            cluster = container.projects()
                    .zones()
                    .clusters()
                    .get(GCP_PROJECT, GCP_LOCATION, CLUSTER_NAME)
                    .execute()
        } catch (GoogleJsonResponseException ex) {
            if (ex.statusCode == 404) {
                return null
            }
        }
        return cluster.getStatus()
    }

    private static HttpCredentialsAdapter loadCredentials() {
        new File("${System.getProperty('user.home')}/.config/gcloud/jenkins-ci-creds.json").withInputStream { inputStream ->
            def userCredentials = GoogleCredentials.fromStream(inputStream)
            def scopedCredentials = userCredentials.createScoped(ContainerScopes.CLOUD_PLATFORM)
            scopedCredentials.refreshIfExpired()
            new HttpCredentialsAdapter(scopedCredentials)
        } as HttpCredentialsAdapter
    }


    static void main(String[] args) {
        new GoogleKubernetesConfiguration().setup()
    }
}


