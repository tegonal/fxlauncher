package fxlauncher;

import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.sun.javafx.application.PlatformImpl;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;


public class Launcher extends Application {
	private static final Logger log = Logger.getLogger("Launcher");

	private Application app;
	private Stage primaryStage;
	private Stage stage;
	private UIProvider uiProvider;
	private StackPane root;

	private final AbstractLauncher<Application> superLauncher = new AbstractLauncher<Application>() {
		@Override
		protected Parameters getParameters() {
			return Launcher.this.getParameters();
		}

		@Override
		protected void updateProgress(double progress) {
			Platform.runLater(() -> uiProvider.updateProgress(progress));
		}

		@Override
		protected void createApplication(Class<Application> appClass) {
			runAndWait(() -> {
				try {
					if (Application.class.isAssignableFrom(appClass)) {
						app = appClass.newInstance();
					} else {
						log.log(Level.INFO, String.format(Constants.getString("Error.Application.Create.1"), appClass));
					}
				} catch (Throwable t) {
					reportError(Constants.getString("Error.Application.Create.2"), t);
				}
			});
		}

		@Override
		protected void reportError(String title, Throwable error) {
			log.log(Level.WARNING, title, error);

			Platform.runLater(() -> {
				Alert alert = new Alert(Alert.AlertType.ERROR);
				alert.setTitle(title);
				alert.setHeaderText(String.format(Constants.getString("Error.Alert.Header"), title,
						System.getProperty("java.io.tmpdir")));
				alert.getDialogPane().setPrefWidth(600);

				ByteArrayOutputStream out = new ByteArrayOutputStream();
				PrintWriter writer = new PrintWriter(out);
				error.printStackTrace(writer);
				writer.close();
				TextArea text = new TextArea(out.toString());
				alert.getDialogPane().setContent(text);

				alert.showAndWait();
				Platform.exit();
			});
		}

		@Override
		protected void setupClassLoader(ClassLoader classLoader) {
			FXMLLoader.setDefaultClassLoader(classLoader);
			Platform.runLater(() -> Thread.currentThread().setContextClassLoader(classLoader));
		}

	};

	/**
	 * Check if a new version is available and return the manifest for the new
	 * version or null if no update.
	 * <p>
	 * Note that updates will only be detected if the application was actually
	 * launched with FXLauncher.
	 *
	 * @return The manifest for the new version if available
	 */
	public static FXManifest checkForUpdate() throws IOException {
		// We might be called even when FXLauncher wasn't used to start the application
		if (AbstractLauncher.manifest == null)
			return null;
		FXManifest manifest = FXManifest.load(URI.create(AbstractLauncher.manifest.uri + "/app.xml"));
		return manifest.equals(AbstractLauncher.manifest) ? null : manifest;
	}

	/**
	 * Initialize the UI Provider by looking for an UIProvider inside the launcher
	 * or fallback to the default UI.
	 * <p>
	 * A custom implementation must be embedded inside the launcher jar, and
	 * /META-INF/services/fxlauncher.UIProvider must point to the new implementation
	 * class.
	 * <p>
	 * You must do this manually/in your build right around the "embed manifest"
	 * step.
	 */
	public void init() throws Exception {
		Iterator<UIProvider> providers = ServiceLoader.load(UIProvider.class).iterator();
		uiProvider = providers.hasNext() ? providers.next() : new DefaultUIProvider();
	}

	public void start(Stage primaryStage) throws Exception {
		this.primaryStage = primaryStage;
		stage = new Stage(StageStyle.UNDECORATED);
		root = new StackPane();
		final boolean[] filesUpdated = new boolean[1];

		Scene scene = new Scene(root);
		stage.setScene(scene);

		superLauncher.setupLogFile();
		superLauncher.checkSSLIgnoreflag();
		this.uiProvider.init(stage);
		root.getChildren().add(uiProvider.createLoader());

		stage.show();

		new Thread(() -> {
			Thread.currentThread().setName("FXLauncher-Thread");
			try {
				superLauncher.updateManifest();
				createUpdateWrapper();
				filesUpdated[0] = superLauncher.syncFiles();
			} catch (Exception ex) {
				log.log(Level.WARNING,
						String.format(Constants.getString("Error.Start.Phase"), superLauncher.getPhase()), ex);
				if (superLauncher.checkIgnoreUpdateErrorSetting()) {
					superLauncher.reportError(
							String.format(Constants.getString("Error.Start.Phase"), superLauncher.getPhase()), ex);
					System.exit(1);
				}
			}

			try {
				superLauncher.createApplicationEnvironment();
				launchAppFromManifest(filesUpdated[0]);
			} catch (Exception ex) {
				superLauncher.reportError(
						String.format(Constants.getString("Error.Start.Phase"), superLauncher.getPhase()), ex);
			}

		}).start();
	}

	private void launchAppFromManifest(boolean showWhatsnew) throws Exception {
		superLauncher.setPhase(Constants.getString("Application.Phase.Prepare"));

		try {
			initApplication();
		} catch (Throwable ex) {
			superLauncher.reportError(Constants.getString("Error.Application.Init"), ex);
		}
		superLauncher.setPhase(Constants.getString("Application.Phase.Start"));
		log.info(() -> Constants.getString("Whatsnew.Log") + showWhatsnew);

		runAndWait(() -> {
			try {
				if (showWhatsnew && superLauncher.getManifest().whatsNewPage != null)
					showWhatsNewDialog(superLauncher.getManifest().whatsNewPage);

				// Lingering update screen will close when primary stage is shown
				if (superLauncher.getManifest().lingeringUpdateScreen) {
					primaryStage.showingProperty().addListener(observable -> {
						if (stage.isShowing())
							stage.close();
					});
				} else {
					stage.close();
				}

				startApplication();
			} catch (Throwable ex) {
				superLauncher.reportError(Constants.getString("Error.Application.Start"), ex);
			}
		});
	}

	private void showWhatsNewDialog(String whatsNewURL) {
		WebView view = new WebView();
		view.getEngine().load(whatsNewURL);
		Alert alert = new Alert(Alert.AlertType.INFORMATION);
		alert.setTitle(Constants.getString("Whatsnew.Title"));
		alert.setHeaderText(Constants.getString("Whatsnew.Header"));
		alert.getDialogPane().setContent(view);
		alert.showAndWait();
	}

	public static void main(String[] args) {
		launch(args);
	}

	private void createUpdateWrapper() {
		superLauncher.setPhase(Constants.getString("Application.Phase.Wrapper"));

		Platform.runLater(() -> {
			Parent updater = uiProvider.createUpdater(superLauncher.getManifest());
			root.getChildren().clear();
			root.getChildren().add(updater);
		});
	}

	public void stop() throws Exception {
		if (app != null)
			app.stop();
	}

	private void initApplication() throws Exception {
		if (app != null) {
			app.init();
		}
	}

	private void startApplication() throws Exception {
		superLauncher.setPhase(Constants.getString("Application.Phase.Init"));
		if (app != null) {
			Parameters appparams = app.getParameters();
			// check if app has parameters
			if (appparams != null) {
				final LauncherParams params = new LauncherParams(getParameters(), superLauncher.getManifest());
				appparams.getNamed().putAll(params.getNamed());
				appparams.getRaw().addAll(params.getRaw());
				appparams.getUnnamed().addAll(params.getUnnamed());
			}
			PlatformImpl.setApplicationName(app.getClass());
			app.start(primaryStage);
		} else {
			// already hide our stage as we don't attach a child javafx application
			stage.hide();
			Path cacheDir = superLauncher.getManifest().resolveCacheDir(getParameters().getNamed());

			String parameters = (superLauncher.getManifest().parameters != null)? " " +superLauncher.getManifest().parameters: "";
			if (superLauncher.getManifest().launchCommand != null) {
				String cmd = superLauncher.getManifest().launchCommand;

				// prefix command with cache directory and parameters from manifest
				String cmdWithPathAndParameters = cacheDir.toAbsolutePath() + File.separator + cmd + parameters;

				// start sub process using a command
				log.info(() -> String.format(Constants.getString("Application.log.Execute"), cmdWithPathAndParameters));

				startSubProcess(cmdWithPathAndParameters);

			} else if (superLauncher.getManifest().launchClass != null){

				//start launchClass as an own process with the assumption that the java command is locally available
				String launchClass = superLauncher.getManifest().launchClass;

				log.info(() -> String.format(Constants.getString("Application.log.Noappclass"), launchClass));

				String classPath = superLauncher.getManifest().files.stream().filter(LibraryFile::loadForCurrentPlatform)
						.map(libraryFile -> cacheDir.toAbsolutePath() + File.separator + libraryFile.file).collect(Collectors.joining(File.pathSeparator));

				String javaCommand = System.getProperty("java.home")+File.separator+"bin"+File.separator+"java";
				log.info(() -> String.format(Constants.getString("Application.log.Execute"), javaCommand, "-cp", classPath, launchClass));

				List<String> allArgs = new ArrayList<>();
				allArgs.add(javaCommand);
				allArgs.add("-cp");
				allArgs.add(classPath);
				allArgs.addAll(superLauncher.getParameters().getRaw());
				allArgs.add(launchClass);

				startSubProcess(allArgs.toArray(new String[allArgs.size()]));
			} else {
				log.info(() -> "Could not start child process, neither launchCommand nor launchClass defined");
				System.exit(-1);
			}
		}
	}

	private void startSubProcess(String... cmd) throws IOException, InterruptedException {
		Process process = Runtime.getRuntime().exec(cmd);

		// log error stream for the first seconds to capture if starting failed
		InputStream stderr = process.getErrorStream();
		Thread errorLog = new Thread(() -> {
			try {
				try(InputStreamReader isr = new InputStreamReader(stderr);
					BufferedReader br = new BufferedReader(isr);) {
					String line = null;
					while (true) {
						if ((line = br.readLine()) == null) break;
						log.log(Level.SEVERE, line);
					}

				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
		errorLog.start();
		errorLog.join(2000);
		if (!process.isAlive()) {
			int exitVal = process.exitValue();
			log.info(() -> "Process exitValue: " + exitVal);
		} else {
			log.info(() -> "Successfully started subprocess");
			//shutdown current process to cleanup and unblock sub-process
			System.exit(0);
		}
	}

	/**
	 * Runs the specified {@link Runnable} on the JavaFX application thread and
	 * waits for completion.
	 *
	 * @param action the {@link Runnable} to run
	 * @throws NullPointerException if {@code action} is {@code null}
	 */
	void runAndWait(Runnable action) {
		if (action == null)
			throw new NullPointerException("action");

		// run synchronously on JavaFX thread
		if (Platform.isFxApplicationThread()) {
			action.run();
			return;
		}

		// queue on JavaFX thread and wait for completion
		final CountDownLatch doneLatch = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				action.run();
			} finally {
				doneLatch.countDown();
			}
		});

		try {
			doneLatch.await();
		} catch (InterruptedException e) {
			// ignore exception
		}
	}
}
