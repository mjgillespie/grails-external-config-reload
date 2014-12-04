package grails.plugins.reloadconfig

import org.codehaus.groovy.grails.plugins.GrailsPlugin
import grails.util.Environment
import java.security.MessageDigest

class ReloadConfigService {
	def pluginManager
	def grailsApplication
	List files
	Date lastTimeChecked
	Date lastTimeReloaded
	ReloadableTimer timer
	Boolean automerge
	Boolean notifyWithConfig

	// Notify plugins list - add external-config-reload automatically	
	private def plugins
	public void setPlugins(def pluginList) {
		this.plugins = pluginList ?: []
		if (!this.plugins.contains("external-config-reload"))
			this.plugins << "external-config-reload"
	}

    def notifyPlugins(List changedFiles=null) {
		log.trace("Notifying ${plugins.size()} plugins${changedFiles?' of changed files '+changedFiles:''}")
		plugins.each { plugin ->
			log.debug("Firing onConfigChange event for plugin ${plugin}")
			pluginManager.getGrailsPlugin(plugin)?.notifyOfEvent(GrailsPlugin.EVENT_ON_CONFIG_CHANGE,
					notifyWithConfig ? grailsApplication.config : changedFiles
			)
		}
    }
	
	def checkNow() {
		log.trace("Check now triggered")
		
		// Check for changes
		def changed = []
		files?.each { String fileName ->
			fileName = processFileName(fileName)
				
			File configFile = new File(fileName).absoluteFile
			log.trace("Checking external config file location ${configFile} for changes since ${lastTimeChecked}...")
			if (configFile.exists() && configFile.lastModified()>lastTimeChecked.time) {
				log.info("Detected changed configuration in ${configFile.name}, reloading configuration")
				try {
					ConfigSlurper configSlurper = new ConfigSlurper(Environment.getCurrent().getName())
					ConfigObject updatedConfig
					if (fileName?.toLowerCase()?.endsWith(".properties")) {
						def props = new Properties()
						configFile.withInputStream { stream ->
							props.load(stream)
						}
						updatedConfig = configSlurper.parse(props)
					} else {
						updatedConfig = configSlurper.parse(configFile.text)
					}
					grailsApplication.config.merge(updatedConfig)
				} catch (Throwable e) {
					log.error("Failed parsing and merging config file ${configFile} changes", e)
				}
				changed << configFile
			}
		}
		
		// Reset last checked date
		lastTimeChecked = new Date()
		
		// Notify plugins
		if (changed) {
			lastTimeReloaded = new Date();
			notifyPlugins(changed);
		}
	}
	
	def reloadNow() {
		log.info("Manual reload of configuration files triggered")
		files?.each { String fileName ->
			fileName = processFileName(fileName)
			
			File configFile = new File(fileName).absoluteFile
			if (configFile.exists()) {
				if (automerge) {
					try {
						log.debug("Reloading ${configFile} manually")
						ConfigSlurper configSlurper = new ConfigSlurper(Environment.getCurrent().getName())
						ConfigObject updatedConfig
						if (fileName?.toLowerCase()?.endsWith(".properties")) {
							def props = new Properties()
							configFile.withInputStream { stream ->
								props.load(stream)
							}
							updatedConfig = configSlurper.parse(props)
						} else {
							updatedConfig = configSlurper.parse(configFile.text)
						}
						grailsApplication.config.merge(updatedConfig)
					} catch (Throwable e) {
						log.error("Failed parsing and merging config file ${configFile} changes", e)
					}
				} else
					log.debug("Not performing auto merge of ${configFile} due to configuration")
			} else {
				log.warn("File ${configFile} does not exist, cannot reload")
			}
		}
		lastTimeReloaded = new Date();
		lastTimeChecked = new Date();
		notifyPlugins();
	}
	
	private String processFileName(String fileName) {
		if (fileName.startsWith('url')) {
			String tempdir = System.getProperty("java.io.tmpdir")
			String url = fileName.substring(fileName.indexOf(':')+1)			
			
			def fileExension = fileName.substring(fileName.lastIndexOf('.')+1)
			def file = File.createTempFile('ConfigLoad', fileExension)
			def fileStream = file.newOutputStream()
			fileStream << new URL(url).openStream()
			fileStream.close()
			
			def md5UrlHash = md5(url)		
			def md5hash = md5(file)
					
			String configFileName = "${md5UrlHash}.${md5hash}.${fileExension}"
			File urlConfigFile = new File(tempdir, configFileName)
			
			if (!urlConfigFile.exists()) {
				// The file has not been found
				// get all files file md5UrlHash.*.extension
				def pattern = ~"${md5UrlHash}.*.${fileExension}"
				new File( tempdir ).eachFileMatch(pattern) { f->
					f.delete()
				}
				file.renameTo(urlConfigFile.absolutePath)
			}
			
			fileName = urlConfigFile.absolutePath
		}
		
		if (fileName.contains("file:")) {
			fileName = fileName.substring(fileName.indexOf(':')+1)
		}
			
		fileName
	}
	
	def md5( obj ) {
		def hash = MessageDigest.getInstance( 'MD5' ).with {
			if (obj instanceof String) {
				obj = new ByteArrayInputStream(obj.getBytes("UTF-8"))
			}
			obj.eachByte( 8192 ) { bfr, num ->
				update bfr, 0, num
			}
			it.digest()
		}
		new BigInteger( 1, hash ).toString( 16 ).padLeft( 32, '0' )
	}
	
}
