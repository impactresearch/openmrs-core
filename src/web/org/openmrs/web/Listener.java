package org.openmrs.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.Module;
import org.openmrs.module.ModuleFactory;
import org.openmrs.module.web.WebModuleUtil;
import org.openmrs.util.OpenmrsClassLoader;
import org.openmrs.util.OpenmrsUtil;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.WebApplicationContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Our Listener class performs the basic starting functions for our webapp.
 * Basic needs for starting the API:
 *  1) Get the runtime properties
 *  2) Start Spring
 *  3) Start the OpenMRS APi (via Context.startup)
 * 
 * Basic startup needs specific to the web layer:
 *  1) Do the web startup of the modules
 *  2) Copy the custom look/images/messages over into the web layer
 *  
 * @author bwolfe
 */
public final class Listener extends ContextLoaderListener {

	private Log log = LogFactory.getLog(this.getClass());
	
	/**
	 * This method is called when the servlet context is initialized(when the
	 * Web Application is deployed). You can initialize servlet context related
	 * data here.
	 * 
	 * @param event
	 */
	public void contextInitialized(ServletContextEvent event) {
		log.debug("Initializing OpenMRS");
		ServletContext servletContext = event.getServletContext();
		String realPath = event.getServletContext().getRealPath("");
		
		/** 
		 * Get the runtime properties and set it to the context
		 * so that they can be used during sessionFactory creation
		 */
		Properties props = getRuntimeProperties();
		Context.setRuntimeProperties(props);
		
		Thread.currentThread().setContextClassLoader(OpenmrsClassLoader.getInstance());
		
		// clear the dwr file
		String absPath = realPath + "/WEB-INF/dwr-modules.xml";
		File dwrFile = new File(absPath.replace("/", File.separator));
		if(dwrFile.exists()) {
			try {
				DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
				DocumentBuilder db = dbf.newDocumentBuilder();
				db.setEntityResolver(new EntityResolver() {
						public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
							// When asked to resolve external entities (such as a DTD) we return an InputSource
							// with no data at the end, causing the parser to ignore the DTD.
							return new InputSource(new StringReader(""));
						}
					});
				Document doc = db.parse(dwrFile);
				Element elem = doc.getDocumentElement();
				elem.setTextContent("");
				OpenmrsUtil.saveDocument(doc, dwrFile);
			}
			catch (IOException io) {
				log.warn("Unable to parse the simple xml string", io);
			} catch (ParserConfigurationException parseError) {
				log.warn("Unable to clear the dwr document", parseError);
			} catch (SAXException sax) {
				log.warn("Unable to clear the dwr document", sax);
			} catch (Exception e) {
				log.debug("Error clearing dwr-modules.xml");
			}
		}
		
		try {
			// start the spring context
			super.contextInitialized(event);
		}
		catch (CannotLoadBeanClassException e) {
			log.warn("Error while initializing spring context.  More than likely caused by an improper shutdown that leaves 1 or more module contexts lying around");
			log.warn("Stacktrace: ", e);
			log.warn("Stopping all modules (most importantly, deleting the context files) and trying again: ");
			
			// Spring places a throwable in the servlet context as the wac if an error occurs.
			// we need to remove it in order to refresh the wac
			servletContext.removeAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);
			
			// retry starting the spring context
			getContextLoader().initWebApplicationContext(servletContext);
		}
		
		/**
		 * Load the core modules from the webapp coreModules folder
		 */
		loadCoreModules(event.getServletContext());
		
		/**
		 * Do the normal API startup now
		 */
		Context.startup(props);
		
		/**
		 * Copy the module messages over into the webapp and perform web portion of startup
		 */
		for (Module mod : ModuleFactory.getStartedModules()) {
			try {
				WebModuleUtil.startModule(mod, event.getServletContext());
			} catch (Exception e) {
				mod.setStartupErrorMessage(e.getMessage());
			}
		}
		
		/** 
		 * Copy the customization scripts over into the webapp
		 */
		// TODO centralize map to WebConstants?
		Map<String, String> custom = new HashMap<String, String>();
		custom.put("custom.template.dir", "/WEB-INF/template");
		custom.put("custom.index.jsp.file", "/WEB-INF/view/index.jsp");
		custom.put("custom.login.jsp.file", "/WEB-INF/view/login.jsp");
		custom.put("custom.patientDashboardForm.jsp.file", "/WEB-INF/view/patientDashboardForm.jsp");
		custom.put("custom.images.dir", "/images");
		custom.put("custom.style.css.file", "/style.css");
		custom.put("custom.messages", "/WEB-INF/custom_messages.properties");
		custom.put("custom.messages_fr", "/WEB-INF/custom_messages_fr.properties");
		custom.put("custom.messages_es", "/WEB-INF/custom_messages_es.properties");
		custom.put("custom.messages_de", "/WEB-INF/custom_messages_de.properties");
		
		for (String prop : custom.keySet()) {
			String webappPath = custom.get(prop);
			String userOverridePath = props.getProperty(prop);
			// if they defined the variable
			if (userOverridePath != null) {
				String absolutePath = realPath + webappPath;
				File file = new File(userOverridePath);

				// if they got the path correct
				// also, if file does not start with a "." (hidden files, like SVN files) 
				if (file.exists() && !userOverridePath.startsWith(".")) {
					log.debug("Overriding file: " + absolutePath);
					log.debug("Overriding file with: " + userOverridePath);
					if (file.isDirectory()) {
						for (File f : file.listFiles()) {
							userOverridePath = f.getAbsolutePath();
							if ( !f.getName().startsWith(".") ) {
								String tmpAbsolutePath = absolutePath + "/"
								+ f.getName();
								if (!copyFile(userOverridePath, tmpAbsolutePath)) {
									log.warn("Unable to copy file in folder defined by runtime property: " + prop);
									log.warn("Your source directory (or a file in it) '" + userOverridePath + " cannot be loaded or destination '" + tmpAbsolutePath + "' cannot be found");
								}
							}
						}
					} else {
						// file is not a directory
						if (!copyFile(userOverridePath, absolutePath)) {
							log.warn("Unable to copy file defined by runtime property: " + prop);
							log.warn("Your source file '" + userOverridePath + " cannot be loaded or destination '" + absolutePath + "' cannot be found");
						}
					}
				}
			}
			
		}
		
	}

	/**
	 * Copies file pointed to by <code>fromPath</code> to <code>toPath</code>
	 * 
	 * @param fromPath
	 * @param toPath
	 * @return true/false whether the copy was a success
	 */
	private boolean copyFile(String fromPath, String toPath) {
		FileInputStream inputStream = null;
		FileOutputStream outputStream = null;
		try {
			inputStream = new FileInputStream(fromPath);
			outputStream = new FileOutputStream(toPath);
			OpenmrsUtil.copyFile(inputStream, outputStream);
		}
		catch (IOException io) {
			return false;
		}
		finally {
			try {
				if (inputStream != null)
					inputStream.close();
			}
			catch (IOException io) {
				log.warn("Unable to close input stream", io);
			}
			try {
				if (outputStream != null)
					outputStream.close();
			}
			catch (IOException io) {
				log.warn("Unable to close input stream", io);
			}
		}
		return true;
	}

	/**
	 * Load the core modules
	 */
	private void loadCoreModules(ServletContext servletContext) {
		String path = servletContext.getRealPath("");
		path += File.separator + "WEB-INF" + File.separator + "coreModules";
		File folder = new File(path);
		
		if (!folder.exists()) {
			log.warn("Core module repository doesn't exist: "
					+ folder.getAbsolutePath());
			return;
		}
		if (!folder.isDirectory()) {
			log.warn("Core module repository isn't a directory: "
					+ folder.getAbsolutePath());
			return;
		}

		// loop over the modules and load the modules that we can
		for (File f : folder.listFiles()) {
			if (!f.getName().startsWith(".")) { // ignore .svn folder and the like
				try {
					Module mod = ModuleFactory.loadModule(f);
					log.debug("Loaded module: " + mod + " successfully");
				}
				catch (Exception e) {
					log.warn("Error while trying to load module " + f.getName() + "", e);
				}
			}
		}
	}
	
	/**
	 * Called when the webapp is shut down properly
	 * Must call Context.shutdown() and then shutdown all 
	 * the web layers of the modules
	 */
	public void contextDestroyed(ServletContextEvent event) {

		Context.shutdown();
		
		WebModuleUtil.shutdownModules(event.getServletContext());
		
		super.contextDestroyed(event);
		
		/*
		// DriverManager cleanup code taken from
		// http://opensource2.atlassian.com/confluence/spring/pages/viewpage.action?pageId=2669
		try {
			Enumeration drivers = DriverManager.getDrivers();
			while (drivers.hasMoreElements()) {
				Driver o = (Driver) drivers.nextElement();
				if (doesClassLoaderMatch(o)) {
					// log.debug("The current driver '" + o + "' is being
					// deregistered.");
					try {
						DriverManager.deregisterDriver(o);
					} catch (SQLException e) {
						throw new RuntimeException(e);
					}
				} else {
					// log.info("Driver '" + o + "' wasn't loaded by this
					// webapp, so not touching it.");
				}
			}

			// log.debug("flushing caches");

		} catch (Throwable e) {
			// log.error("Failed to cleanup ClassLoader for webapp");
		}

		// log.debug("undeploying application : LogFactory() being destroyed");
		// LogFactory.release(Thread.currentThread().getContextClassLoader());
		LogFactory.releaseAll();
		  
		 */
	}

	//private boolean doesClassLoaderMatch(Driver o) {
	//	return o.getClass().getClassLoader() == this.getClass()
	//			.getClassLoader();
	//}
	
	/**
	 * Looks for and loads in the runtime properties.
	 * Searches for an the file in this order:
	 * 1) environment variable called "OPENMRS_RUNTIME_PROPERTIES_FILE"
	 * 2) {user_home}/WEBAPPNAME_runtime.properties
	 * 3) ./WEBAPPNAME_runtime.properties
	 * 
	 * @return Properties
	 */
	public static Properties getRuntimeProperties() {
		Log log = LogFactory.getLog(Listener.class);
		
		Properties props = new Properties();
		
		try {
			FileInputStream propertyStream = null;

			// Look for environment variable {WEBAPP.NAME}_RUNTIME_PROPERTIES_FILE
			String webapp = WebConstants.OPENMRS_WEBAPP_NAME;
			webapp = webapp.toUpperCase();
			String env = webapp + "_RUNTIME_PROPERTIES_FILE";
			
			String filepath = System.getenv(env);

			if (filepath != null) {
				log.debug("Loading runtime properties from: " + filepath);
				try {
					propertyStream = new FileInputStream(filepath);
				}
				catch (IOException e) { }
			} else {
				log.warn("Couldn't find an environment variable named " + env);
				log.debug("Available environment variables are named: " + System.getenv().keySet());
			}

			// env is the name of the file to look for in the directories
			String filename = webapp + "-runtime.properties";
			
			if (propertyStream == null) {
				filepath = OpenmrsUtil.getApplicationDataDirectory() + filename;
				log.warn("Looking for property file: " + filepath);
				try {
					propertyStream = new FileInputStream(filepath);
				}
				catch (IOException e) { }	
			}
			
			// look in current directory last
			if (propertyStream == null) {
				filepath = filename;
				log.warn("Looking for property file in current directory: " + filepath);
				try {
					propertyStream = new FileInputStream(filepath);
				}
				catch (IOException e) { }
			}
			
			if (propertyStream == null)
				log.warn("Could not open '" + filename + "' in user or local directory.");
			else {
				props.load(propertyStream);
				propertyStream.close();
			}

		} catch (IOException e) {
			log.warn("Unable to load properties file. Starting with default properties.", e);
		}
		
		return props;
	}

}