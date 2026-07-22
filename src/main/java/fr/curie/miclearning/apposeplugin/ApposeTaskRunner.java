package fr.curie.miclearning.apposeplugin;
import fr.curie.miclearning.tools.appose.ApposeUtils;
import ij.IJ;
import ij.gui.ProgressBar;
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
 * resource, runs a bundled python script with an inputs map
 * <p>
 * Supports two usage patterns :
 *  * <ul>
 *  *   <li>{@link #runBlocking} - streaming: the script reports results one per frame
 *  *       via task events, which are pushed onto a queue as they arrive.</li>
 *  *   <li>{@link #runAndGetOutputs} - single-shot: the script computes everything and the
 *  *       result is read once from {@code task.outputs}.</li>
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

    private String loadScriptOrThrow() throws IOException {
        String script = ApposeUtils.getResourceAsString(scriptResourcePath);
        if (script == null) {
            throw new IOException("Unable to load script: " + scriptResourcePath);
        }
        IJ.log("Python script loaded");
        return script;
    }

    private void requireInitialized() {
        if (environment == null) {
            throw new IllegalStateException("ApposeTaskRunner.initialize() must be called before running a task");
        }
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
            throws TaskException, InterruptedException, IOException {
        requireInitialized();

        String script;
        try {
            script = loadScriptOrThrow();
        } catch (IOException e) {
            IJ.error("Unable to load resource", e.getMessage());
            resultsQueue.offer(endSignal);
            throw e;
        }

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
                            if (event.current > 0 && event.maximum >0) {
                                System.out.println(" python task: " + event.current + "/" + event.maximum);
                                IJ.showProgress(Math.toIntExact(event.current-1), Math.toIntExact(event.maximum));
                            }
                            if (info != null) {
                                resultsQueue.offer(info);
                            }
                        }
                        break;
                    case CRASH:
                        System.out.println("Python task crashed : " + task.error);
                        IJ.log("Python task crashed: " + task.error);
                        IJ.error("Python task crashed", String.valueOf(task.error));
                        resultsQueue.offer(endSignal);
                        break;
                    case FAILURE:
                        System.out.println("Python task failed with error: " + task.error);
                        IJ.log("Python task failed: " + task.error);
                        IJ.error("Python task failed", String.valueOf(task.error));
                        resultsQueue.offer(endSignal);
                        break;
                    case COMPLETION:
                        System.out.println("Python task completed successfully");
                        resultsQueue.offer(endSignal);
                        break;
                    case CANCELATION:
                        System.out.println("Python task canceled");
                        resultsQueue.offer(endSignal);
                        break;
                    default:
                        break;
                }
            });

            IJ.log("Executing python script...");
            task.waitFor();
        }
    }

    /**
     * Runs the bundled script with the given inputs, blocking until the task reaches a
     * terminal state, then returns the task's final outputs map.
     */
    public Map<String, Object> runAndGetOutputs(Map<String, Object> inputs)
            throws TaskException, InterruptedException, IOException {
        requireInitialized();
        String script = loadScriptOrThrow();

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
                        }
                        break;
                    case CRASH:
                        System.out.println("Python task crashed : " + task.error);
                        IJ.log("Python task crashed: " + task.error);
                        IJ.error("Python task crashed", String.valueOf(task.error));
                        break;
                    case FAILURE:
                        System.out.println("Python task failed with error: " + task.error);
                        IJ.log("Python task failed: " + task.error);
                        IJ.error("Python task failed", String.valueOf(task.error));
                        break;
                    case COMPLETION:
                        System.out.println("Python task completed successfully");;
                        break;
                    case CANCELATION:
                        System.out.println("Python task canceled");
                        break;
                    default:
                        break;
                }
            });

            IJ.log("Executing python script...");
            task.waitFor();
            return task.outputs;
        }
    }

    @Override
    public void close() {
        environment = null;
    }
}
