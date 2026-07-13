package fr.curie.miclearning.apposeplugin;
import fr.curie.miclearning.tools.appose.ApposeUtils;
import ij.IJ;
import org.apposed.appose.Appose;
import org.apposed.appose.BuildException;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;
import org.apposed.appose.TaskException;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Manage Appose environment/task lifecycle: builds a pixi environment from a TOML
 * resource, runs a bundled python script with a caller-supplied inputs map, and streams
 * per-item results onto a queue until the task reaches a terminal state.
 *  */

public class ApposeTaskRunner implements AutoCloseable {

    private final String scriptResourcePath;
    private final String envTomlResourcePath;
    private Environment environment;

    public ApposeTaskRunner(String scriptResourcePath, String envTomlResourcePath) {
        this.scriptResourcePath = scriptResourcePath;
        this.envTomlResourcePath = envTomlResourcePath;
    }

    /** Loads the environment TOML and builds the pixi environment. Call once before {@link #runBlocking}. */
    public void initialize() throws IOException, BuildException {
        String envTomlContent = ApposeUtils.getResourceAsString(envTomlResourcePath);
        if (envTomlContent == null) {
            throw new IOException("Unable to load environment resource: " + envTomlResourcePath);
        }
        this.environment = Appose.pixi().content(envTomlContent).build();
        IJ.log("Python environment built");
    }

    /**
     * Runs the script with the given inputs, blocking until the task reaches a terminal state .
     *
     * @param inputs       appose inputs map
     * @param endSignal    signal pushed once processing is done, whatever the outcome
     * @param resultsQueue destination queue results
     */
    public void runBlocking(Map<String, Object> inputs, Map<String, Object> endSignal,
                            BlockingQueue<Map<String, Object>> resultsQueue)
            throws TaskException, InterruptedException {
        if (environment == null) {
            throw new IllegalStateException("ApposeTaskRunner.initialize() must be called before runBlocking()");
        }

        String script = ApposeUtils.getResourceAsString(scriptResourcePath);
        if (script == null) {
            IJ.error("Unable to load resource", "Unable to load script: " + scriptResourcePath);
            resultsQueue.offer(endSignal);
            return;
        }
        IJ.log("Python script loaded");

        try (Service python = environment.python()) {
            Service.Task task = python.task(script, inputs);

            task.listen(event -> {
                switch (event.responseType) {
                    case LAUNCH:
                        System.out.println("Python task launched");
                        break;
                    case UPDATE:
                        if (event.message != null && !event.message.isEmpty()) {
                            IJ.log("   " + event.message);
                            Map<String, Object> info = event.info;
                            if (event.current > 0 && event.maximum >0)
                                System.out.println(" python task: " + event.current + "/" + event.maximum);
                            if (info != null) {
                                resultsQueue.offer(info);
                            }
                        }
                        break;
                    case CRASH:
                        System.out.println("Python task crashed : " + task.error);
                        IJ.log("Python task crashed: " + task.error);
                        IJ.error("Python task crashed", String.valueOf(task.error));
                        resultsQueue.offer(VideoDetectionConsumer.END_SIGNAL);
                        break;
                    case FAILURE:
                        System.out.println("Python task failed with error: " + task.error);
                        IJ.log("Python task failed: " + task.error);
                        IJ.error("Python task failed", String.valueOf(task.error));
                        resultsQueue.offer(VideoDetectionConsumer.END_SIGNAL);
                        break;
                    case COMPLETION:
                        System.out.println("Python task completed successfully");
                        resultsQueue.offer(VideoDetectionConsumer.END_SIGNAL);
                        break;
                    case CANCELATION:
                        System.out.println("Python task canceled");
                        resultsQueue.offer(VideoDetectionConsumer.END_SIGNAL);
                        break;
                    default:
                        break;
                }
            });

            IJ.log("Executing python script...");
            task.waitFor();
        }
    }

    @Override
    public void close() {
        environment = null;
    }
}
