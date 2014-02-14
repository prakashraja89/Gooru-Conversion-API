package org.ednovo.gooru.converter.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.ednovo.gooru.application.converter.ConversionAppConstants;
import org.ednovo.gooru.application.converter.GooruImageUtil;
import org.ednovo.gooru.application.converter.PdfToImageRenderer;
import org.json.CDL;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import atg.taglib.json.util.JSONObject;

import com.google.code.javascribd.connection.ScribdClient;
import com.google.code.javascribd.connection.StreamableData;
import com.google.code.javascribd.docs.Upload;
import com.google.code.javascribd.type.Access;
import com.google.code.javascribd.type.ApiKey;
import com.google.code.javascribd.type.FileData;

@Service
public class ConversionServiceImpl implements ConversionService, ConversionAppConstants {

	private static final Logger logger = LoggerFactory.getLogger(GooruImageUtil.class);

	@Override
	public List<String> resizeImageByDimensions(String srcFilePath, String targetFolderPath, String dimensions, String resourceGooruOid, String sessionToken, String thumbnail, String apiEndPoint) {
		return resizeImageByDimensions(srcFilePath, targetFolderPath, null, dimensions, resourceGooruOid, sessionToken, thumbnail, apiEndPoint);
	}

	@Override
	public void scribdUpload(String apiKey, String dockey, String filepath, String gooruOid, String authXml) {
		ScribdClient client = new ScribdClient();

		ApiKey apikey = new ApiKey(apiKey);

		File file = new File(filepath);

		StreamableData uploadData = new FileData(file);

		// initialize upload method
		Upload upload = new Upload(apikey, uploadData);
		upload.setDocType(PDF);
		upload.setAccess(Access.PRIVATE);
		upload.setRevId(new Integer(dockey));

		try {
			client.execute(upload);
		} catch (Exception e) {
			logger.info("$ Textbook upload failed ", e);
		}

		try {
			File srcFile = new File(filepath);

			new PdfToImageRenderer().process(filepath, srcFile.getParent(), false, JPG, gooruOid, authXml);

		} catch (Exception ex) {
			logger.info("$ Generation of pdf slides failed ", ex);
		}
	}

	@Override
	public void resizeImage(String command, String logFile) {
		logger.info("Runtime Executor .... Initializing...");
		try {
			command = StringUtils.replace(command, "/usr/bin/convert", "/usr/bin/gm@convert");
			String cmdArgs[] = applyTemporaryFixForPath(command.split("@"));
			Process thumsProcess = Runtime.getRuntime().exec(cmdArgs);
			thumsProcess.waitFor();

			String line;
			StringBuffer sb = new StringBuffer();

			BufferedReader in = new BufferedReader(new InputStreamReader(thumsProcess.getInputStream()));
			while ((line = in.readLine()) != null) {
				sb.append(line).append("\n");
			}
			in.close();

			logger.info("output : {} -  Status : {} - Command : " + StringUtils.join(cmdArgs, " "), sb.toString(), thumsProcess.exitValue() + "");

			if (logFile != null) {
				FileUtils.writeStringToFile(new File(logFile), "Completed");
			}
		} catch (Exception e) {
			logger.error("something went wrong while converting image", e);
		}

	}

	private static String[] applyTemporaryFixForPath(String[] cmdArgs) {
		if (cmdArgs.length > 0) {
			String[] fileTypes = { ".jpeg", ".JPEG", ".jpg", ".JPG", ".png", ".PNG", ".gif", ".GIF" };
			for (int argIndex = 0; argIndex < cmdArgs.length; argIndex++) {
				String part = cmdArgs[argIndex];
				if (containsIgnoreCaseInArray(part, fileTypes)) {
					String filePath = null;
					String repoPath = StringUtils.substringBeforeLast(part, "/");
					String remainingPart = StringUtils.substringAfter(part, "/");
					for (String fileType : fileTypes) {
						filePath = StringUtils.substringAfter(remainingPart, fileType);
						if (!filePath.isEmpty()) {
							break;
						}
					}

					if (!filePath.isEmpty()) {
						if (!repoPath.isEmpty()) {
							filePath = repoPath + "/" + filePath;
						}
						cmdArgs[argIndex] = filePath;
					}
				}
			}
		}
		return cmdArgs;
	}

	private static boolean containsIgnoreCaseInArray(String haystack, String[] containsArray) {
		for (String key : containsArray) {
			if (haystack.contains(key)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void convertPdfToImage(String resourceFilePath, String gooruOid, String authXml) {
		logger.warn("$ Processing  pdf slides conversion request for : " + resourceFilePath);

		try {
			File srcFile = new File(resourceFilePath);

			new PdfToImageRenderer().process(resourceFilePath, srcFile.getParent(), false, JPG, gooruOid, authXml);

		} catch (Exception ex) {
			logger.info("$ Generation of pdf slides failed ", ex);
		}
	}

	public List<String> resizeImageByDimensions(String srcFilePath, String targetFolderPath, String filenamePrefix, String dimensions, String resourceGooruOid, String sessionToken, String thumbnail, String apiEndPoint) {
		String imagePath = null;
		List<String> list = new ArrayList<String>();
		try {
			logger.debug(" src : {} /= target : {}", srcFilePath, targetFolderPath);
			String[] imageDimensions = dimensions.split(",");
			if (filenamePrefix == null) {
				filenamePrefix = StringUtils.substringAfterLast(srcFilePath, "/");
				if (filenamePrefix.contains(".")) {
					filenamePrefix = StringUtils.substringBeforeLast(filenamePrefix, ".");
				}
			}

			for (String dimension : imageDimensions) {
				String[] xy = dimension.split(X);
				int width = Integer.valueOf(xy[0]);
				int height = Integer.valueOf(xy[1]);
				imagePath = resizeImageByDimensions(srcFilePath, width, height, targetFolderPath + filenamePrefix + "-" + dimension + "." + getFileExtenstion(srcFilePath));

				list.add(imagePath);
			}

			try {
				JSONObject json = new JSONObject();
				json.put(RESOURCE_GOORU_OID, resourceGooruOid);
				json.put(THUMBNAIL, thumbnail);
				JSONObject jsonAlias = new JSONObject();
				jsonAlias.put(MEDIA, json);
				String jsonString = jsonAlias.toString();
				StringRequestEntity requestEntity = new StringRequestEntity(jsonString, APP_JSON, "UTF-8");
				HttpClient client = new HttpClient();
				PostMethod postmethod = new PostMethod(apiEndPoint + "/media/resource/thumbnail?sessionToken=" + sessionToken);
				postmethod.setRequestEntity(requestEntity);
				client.executeMethod(postmethod);
			} catch (Exception ex) {
				logger.error("rest api call failed!", ex.getMessage());
			}

		} catch (Exception ex) {
			logger.error("Multiple scaling of image failed for src : " + srcFilePath + " : ", ex);
		}

		return list;
	}

	public String resizeImageByDimensions(String srcFilePath, int width, int height, String destFilePath) throws Exception {
		String imagePath = null;
		try {
			logger.debug(" src : {} /= target : {}", srcFilePath, destFilePath);
			File destFile = new File(destFilePath);
			if (new File(srcFilePath).exists() && destFile.exists()) {
				destFile.delete();
			}
			scaleImageUsingImageMagick(srcFilePath, width, height, destFilePath);
			imagePath = destFile.getPath();
		} catch (Exception ex) {
			logger.error("Error while scaling image", ex);
			throw ex;
		}

		return imagePath;
	}

	public void scaleImageUsingImageMagick(String srcFilePath, int width, int height, String destFilePath) throws Exception {
		try {
			String resizeCommand = new String("/usr/bin/gm@convert@" + srcFilePath + "@-resize@" + width + X + height + "@" + destFilePath);
			String cmdArgs[] = resizeCommand.split("@");
			Process thumsProcess = Runtime.getRuntime().exec(cmdArgs);
			thumsProcess.waitFor();

			String line;
			StringBuffer sb = new StringBuffer();

			BufferedReader in = new BufferedReader(new InputStreamReader(thumsProcess.getInputStream()));
			while ((line = in.readLine()) != null) {
				sb.append(line).append("\n");
			}
			in.close();

			logger.info("output : {} -  Status : {} - Command : " + StringUtils.join(cmdArgs, " "), sb.toString(), thumsProcess.exitValue() + "");

		} catch (Exception e) {
			logger.error("something went wrong while converting image", e);
		}

	}

	@Override
	public String convertHtmlToPdf(String htmlContent, String targetPath, String sourceHtmlUrl, String filename) {
		File targetDir = new File(targetPath);
		if (!targetDir.exists()) {
			targetDir.mkdirs();
		}
		if (filename == null) {
			filename = String.valueOf(System.currentTimeMillis());
		} else {
			File file = new File(targetPath + filename + DOT_PDF);
			if (file.exists()) {
				filename = filename + "-" + System.currentTimeMillis();
			}
		}
		if (htmlContent != null) {
			if (saveAsHtml(targetPath + filename + DOT_HTML, htmlContent) != null) {
				convertHtmlToPdf(targetPath + filename + DOT_HTML, targetPath + filename + DOT_PDF, 0);
				File file = new File(targetPath + filename + DOT_HTML);
				file.delete();
				return filename + DOT_PDF;
			}
		} else if (sourceHtmlUrl != null) {
			convertHtmlToPdf(sourceHtmlUrl, targetPath + filename + DOT_PDF, 30000);
			return filename + DOT_PDF;
		}

		return null;
	}

	private static String saveAsHtml(String fileName, String content) {
		File file = new File(fileName);
		try {
			FileOutputStream fop = new FileOutputStream(file);
			if (!file.exists()) {
				file.createNewFile();
			}
			byte[] contentInBytes = content.getBytes();

			fop.write(contentInBytes);
			fop.flush();
			fop.close();
			return fileName;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}

	private static void convertHtmlToPdf(String srcFileName, String destFileName, long delayInMillsec) {
		try {
			String resizeCommand = new String("/usr/bin/wkhtmltopdf@" + srcFileName + "@--redirect-delay@" + delayInMillsec + "@" + destFileName);
			String cmdArgs[] = resizeCommand.split("@");
			Process thumsProcess = Runtime.getRuntime().exec(cmdArgs);
			thumsProcess.waitFor();

		} catch (Exception e) {
			logger.error("something went wrong while converting pdf", e);
		}
	}

	public static String getFileExtenstion(String filePath) {

		return StringUtils.substringAfterLast(filePath, ".");

	}

	public String convertJsonToCsv(String jsonString, String targetFolderPath, String filename) {
		try {
			JSONArray docs = new JSONArray(jsonString);
			File file = new File(targetFolderPath + filename + DOT_CSV);
			String csv = CDL.toString(docs);
			if (file.exists()) {
				filename = filename + "-" + System.currentTimeMillis() + DOT_CSV;
			} else {
				filename = filename + DOT_CSV;
				file = new File(targetFolderPath + filename);
			}
			FileUtils.writeStringToFile(file, csv);
			return filename;

		} catch (Exception ex) {
			logger.info("$ Conversion of Csv file failed ", ex);
		}
		return null;
	}

}