import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.container.v1beta1.Container
import com.google.api.services.container.v1beta1.ContainerScopes
import com.google.api.services.container.v1beta1.model.AddonsConfig
import com.google.api.services.container.v1beta1.model.Cluster
import com.google.api.services.container.v1beta1.model.CreateClusterRequest
import com.google.api.services.container.v1beta1.model.IstioConfig
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.ServiceAccountCredentials
import io.kubernetes.client.util.FilePersister

import java.util.concurrent.TimeUnit

import static org.awaitility.Awaitility.await

@Grapes([
    @Grab(group = "com.google.auth", module = "google-auth-library-oauth2-http", version = "0.16.2"),
    @Grab(group = "com.google.api-client", module = "google-api-client", version = "1.30.1"),
    @Grab(group = "com.google.apis", module = "google-api-services-container", version = "v1beta1-rev85-1.25.0"),
    @Grab(group = "org.awaitility", module = "awaitility-groovy", version = "3.1.6"),
    @Grab(group = "io.kubernetes", module = "client-java", version = "5.0.0")
])
class GoogleKubernetesConfiguration {

    private final def APPLICATION_NAME = "spring-cloud-kubernetes-ci-build"
    private final def HTTP_TRANSPORT = new NetHttpTransport()
    private final def JSON_FACTORY = new JacksonFactory()
    private final def GCP_LOCATION = "europe-west2-a"
    private final def GCP_PROJECT = "cf-sandbox-ohughes"
    private final def CLUSTER_NAME = "${APPLICATION_NAME}-cluster"
    private final def KUBECONFIG_FILE = getClass().getResource("out/jenkins-kubeconfig.yml")

    void setup() {
        def credentials = loadCredentials()
        def gkeClient = new Container.Builder(HTTP_TRANSPORT, JSON_FACTORY, new HttpCredentialsAdapter(credentials))
            .setApplicationName(APPLICATION_NAME)
            .build()

        def request = buildClusterRequest()

        gkeClient.projects()
            .zones()
            .clusters()
            .create(GCP_PROJECT, GCP_LOCATION, request)
            .execute()

        await().atMost(10, TimeUnit.MINUTES)
            .pollInterval(5, TimeUnit.SECONDS)
            .until { getCluster(gkeClient).getStatus() == "RUNNING" }

        createKubeConfig(gkeClient, credentials)
    }

    void destroy() {
        def gkeClient = new Container.Builder(HTTP_TRANSPORT, JSON_FACTORY, new HttpCredentialsAdapter(loadCredentials()))
            .setApplicationName(APPLICATION_NAME)
            .build()

//        gkeClient.projects()
//            .zones()
//            .clusters()
//            .delete(GCP_PROJECT, GCP_LOCATION, CLUSTER_NAME)
//            .execute()

        await().atMost(10, TimeUnit.MINUTES)
            .ignoreException(GoogleJsonResponseException.class)
            .pollInterval(5, TimeUnit.SECONDS)
            .until { getCluster(gkeClient).getStatus() == null }
    }

    private void createKubeConfig(Container gkeClient, ServiceAccountCredentials credentials) {
        def clusterInfo = getCluster(gkeClient)
        def clusters =
            [[
                 cluster: [
                     ("certificate-authority-data"): clusterInfo.getMasterAuth().getClusterCaCertificate(),
                     server                        : 'https://' + clusterInfo.getEndpoint()
                 ],
                 name   : clusterInfo.getName()
             ]] as ArrayList

        def users =
            [[
                 name: clusterInfo.getName(),
                 user: [
                     token: credentials.getAccessToken().getTokenValue()
                 ]
             ]] as ArrayList

        def contexts =
            [[
                 context: [
                     user   : clusterInfo.getName(),
                     cluster: clusterInfo.getName()
                 ],
                 name   : clusterInfo.getName()
             ]] as ArrayList

        def persister = new FilePersister(KUBECONFIG_FILE.path)
        persister.save(contexts, clusters, users, null, clusterInfo.getName())
    }

    private CreateClusterRequest buildClusterRequest() {
        def request = new CreateClusterRequest()
        def cluster = new Cluster()
        cluster.setName(CLUSTER_NAME)
        cluster.setInitialNodeCount(1)
        cluster.setInitialClusterVersion("latest")
        def addonsConfig = new AddonsConfig()
        def istioConfig = new IstioConfig()
        istioConfig.setDisabled(false)
        addonsConfig.setIstioConfig(istioConfig)
        cluster.setAddonsConfig(addonsConfig)
        request.setCluster(cluster)
        request
    }

    private Cluster getCluster(Container container) {
        container.projects()
            .zones()
            .clusters()
            .get(GCP_PROJECT, GCP_LOCATION, CLUSTER_NAME)
            .execute()
    }

    private static ServiceAccountCredentials loadCredentials() {
        new File(System.getProperty('GCP_SERVICE_CREDENTIALS')).withInputStream { inputStream ->
            def userCredentials = ServiceAccountCredentials.fromStream(inputStream)
            def scopedCredentials = userCredentials.createScoped(ContainerScopes.CLOUD_PLATFORM)
            scopedCredentials.refreshIfExpired()
            scopedCredentials
        } as ServiceAccountCredentials
    }

    static void main(String[] args) {
        System.getenv().each { name, value -> println "Name: $name -> Value $value" }

        if (args[0] == "setup") {
            new GoogleKubernetesConfiguration().setup()
        }
        if (args[0] == "destroy") {
            new GoogleKubernetesConfiguration().destroy()
        } else throw new IllegalArgumentException("Argument [setup | destroy] must be provided")
    }
}


