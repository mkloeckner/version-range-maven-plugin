package org.kloeckner.maven.plugin.util;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.MapBasedValueSource;
import org.codehaus.plexus.interpolation.ObjectBasedValueSource;
import org.codehaus.plexus.interpolation.PrefixAwareRecursionInterceptor;
import org.codehaus.plexus.interpolation.PrefixedObjectValueSource;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.WriterFactory;
import org.jdom.CDATA;
import org.jdom.Comment;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.Namespace;
import org.jdom.Text;
import org.jdom.filter.ContentFilter;
import org.jdom.filter.ElementFilter;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;

/**
 */
public class VersionRangeUtils {

	public static final String POMv4 = "pom.xml";

	//	private static final String FS = File.separator;

	private static String ls = VersionRangeUtils.LS;

	/**
	 * The line separator to use.
	 */
	public static final String LS = System.getProperty("line.separator");

	private VersionRangeUtils() {
		// private
	}

	public static MavenProject getRootProject(List<MavenProject> reactorProjects) {
		MavenProject project = reactorProjects.get(0);
		for (MavenProject currentProject : reactorProjects) {
			if (currentProject.isExecutionRoot()) {
				project = currentProject;
				break;
			}
		}
		return project;
	}

	public static String interpolate(String value, Model model) throws MojoExecutionException {
		if (value != null && value.contains("${")) {
			StringSearchInterpolator interpolator = new StringSearchInterpolator();
			List<String> pomPrefixes = Arrays.asList("pom.", "project.");
			interpolator.addValueSource(new PrefixedObjectValueSource(pomPrefixes, model, false));
			interpolator.addValueSource(new MapBasedValueSource(model.getProperties()));
			interpolator.addValueSource(new ObjectBasedValueSource(model));
			try {
				value = interpolator.interpolate(value, new PrefixAwareRecursionInterceptor(pomPrefixes));
			} catch (InterpolationException e) {
				throw new MojoExecutionException("Failed to interpolate " + value + " for project " + model.getId(), e);
			}
		}
		return value;
	}

	public static String rewriteParent(MavenProject project, Element rootElement, Namespace namespace, Map<String, String> mappedVersions, Map<String, String> originalVersions) throws MojoExecutionException {
		String parentVersion = null;
		if (project.hasParent()) {
			Element parentElement = rootElement.getChild("parent", namespace);
			Element versionElement = parentElement.getChild("version", namespace);
			MavenProject parent = project.getParent();
			String key = ArtifactUtils.versionlessKey(parent.getGroupId(), parent.getArtifactId());
			parentVersion = mappedVersions.get(key);
			//			if (parentVersion == null) {
			//				//MRELEASE-317
			//				parentVersion = getResolvedSnapshotVersion(key, resolvedSnapshotDependencies);
			//			}
			if (parentVersion == null) {
				if (parent.getVersion().equals(originalVersions.get(key))) {
					throw new MojoExecutionException("Version for parent '" + parent.getName() + "' was not mapped");
				}
			} else {
				rewriteValue(versionElement, parentVersion);
			}
		}
		return parentVersion;
	}

	public static void rewriteValue(Element element, String value) {
		Text text = null;
		if (element.getContent() != null) {
			for (Iterator<?> it = element.getContent().iterator(); it.hasNext();) {
				Object content = it.next();
				if ((content instanceof Text) && ((Text) content).getTextTrim().length() > 0) {
					text = (Text) content;
					while (it.hasNext()) {
						content = it.next();
						if (content instanceof Text) {
							text.append((Text) content);
							it.remove();
						} else {
							break;
						}
					}
					break;
				}
			}
		}
		if (text == null) {
			element.addContent(value);
		} else {
			String chars = text.getText();
			String trimmed = text.getTextTrim();
			int idx = chars.indexOf(trimmed);
			String leadingWhitespace = chars.substring(0, idx);
			String trailingWhitespace = chars.substring(idx + trimmed.length());
			text.setText(leadingWhitespace + value + trailingWhitespace);
		}
	}

	public static void rewriteVersion(Element rootElement, Namespace namespace, Map<String, String> mappedVersions, String projectId, MavenProject project, String parentVersion) throws MojoExecutionException {
		Element versionElement = rootElement.getChild("version", namespace);
		String version = mappedVersions.get(projectId);
		if (version == null) {
			throw new MojoExecutionException("Version for '" + project.getName() + "' was not mapped");
		}

		if (versionElement == null) {
			if (!version.equals(parentVersion)) {
				// we will add this after artifactId, since it was missing but different from the inherited version
				Element artifactIdElement = rootElement.getChild("artifactId", namespace);
				int index = rootElement.indexOf(artifactIdElement);

				versionElement = new Element("version", namespace);
				versionElement.setText(version);
				rootElement.addContent(index + 1, new Text("\n  "));
				rootElement.addContent(index + 2, versionElement);
			}
		} else {
			rewriteValue(versionElement, version);
		}
	}

	public static File getStandardPom(MavenProject project) {
		if (project == null) {
			return null;
		}

		File pom = project.getFile();

		if (pom == null) {
			return null;
		}

		return pom;
	}

	public static List<Element> getChildren(Element root, String... names) {
		Element parent = root;
		for (int i = 0; i < names.length - 1 && parent != null; i++) {
			parent = parent.getChild(names[i], parent.getNamespace());
		}
		if (parent == null) {
			return Collections.emptyList();
		}
		return parent.getChildren(names[names.length - 1], parent.getNamespace());
	}

	/**
	 * Gets the string contents of the specified XML file. Note: In contrast to an XML processor, the line separators in
	 * the returned string will be normalized to use the platform's native line separator. This is basically to save
	 * another normalization step when writing the string contents back to an XML file.
	 * 
	 * @param file The path to the XML file to read in, must not be <code>null</code>.
	 * @return The string contents of the XML file.
	 * @throws IOException If the file could not be opened/read.
	 */
	public static String readXmlFile(File file) throws IOException {
		return readXmlFile(file, LS);
	}

	public static String readXmlFile(File file, String ls) throws IOException {
		Reader reader = null;
		try {
			reader = ReaderFactory.newXmlReader(file);
			return normalizeLineEndings(IOUtil.toString(reader), ls);
		} finally {
			IOUtil.close(reader);
		}
	}

	/**
	 * Normalizes the line separators in the specified string.
	 * 
	 * @param text The string to normalize, may be <code>null</code>.
	 * @param separator The line separator to use for normalization, typically "\n" or "\r\n", must not be
	 *            <code>null</code>.
	 * @return The input string with normalized line separators or <code>null</code> if the string was <code>null</code>
	 *         .
	 */
	public static String normalizeLineEndings(String text, String separator) {
		String norm = text;
		if (text != null) {
			norm = text.replaceAll("(\r\n)|(\n)|(\r)", separator);
		}
		return norm;
	}

	public static void writePom(File pomFile, Document document, String modelVersion, String intro, String outtro) throws MojoExecutionException {
		Element rootElement = document.getRootElement();

		Namespace pomNamespace = Namespace.getNamespace("", "http://maven.apache.org/POM/" + modelVersion);
		rootElement.setNamespace(pomNamespace);
		Namespace xsiNamespace = Namespace.getNamespace("xsi", "http://www.w3.org/2001/XMLSchema-instance");
		rootElement.addNamespaceDeclaration(xsiNamespace);

		if (rootElement.getAttribute("schemaLocation", xsiNamespace) == null) {
			rootElement.setAttribute("schemaLocation", "http://maven.apache.org/POM/" + modelVersion + " http://maven.apache.org/maven-v" + modelVersion.replace('.', '_') + ".xsd", xsiNamespace);
		}

		// the empty namespace is considered equal to the POM namespace, so match them up to avoid extra xmlns=""
		ElementFilter elementFilter = new ElementFilter(Namespace.getNamespace(""));
		for (Iterator<?> i = rootElement.getDescendants(elementFilter); i.hasNext();) {
			Element e = (Element) i.next();
			e.setNamespace(pomNamespace);
		}

		Writer writer = null;
		try {
			writer = WriterFactory.newXmlWriter(pomFile);

			if (intro != null) {
				writer.write(intro);
			}

			Format format = Format.getRawFormat();
			format.setLineSeparator(ls);
			XMLOutputter out = new XMLOutputter(format);
			out.output(document.getRootElement(), writer);

			if (outtro != null) {
				writer.write(outtro);
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Error writing POM: " + e.getMessage(), e);
		} finally {
			IOUtil.close(writer);
		}
	}

	public static void normaliseLineEndings(Document document) {
		for (Iterator<?> i = document.getDescendants(new ContentFilter(ContentFilter.COMMENT)); i.hasNext();) {
			Comment c = (Comment) i.next();
			c.setText(VersionRangeUtils.normalizeLineEndings(c.getText(), ls));
		}
		for (Iterator<?> i = document.getDescendants(new ContentFilter(ContentFilter.CDATA)); i.hasNext();) {
			CDATA c = (CDATA) i.next();
			c.setText(VersionRangeUtils.normalizeLineEndings(c.getText(), ls));
		}
	}

	public static List<String> loadEagerDependencies(String path, String filename) throws MojoExecutionException {
		if (path == null) {
			path = Paths.get("").toAbsolutePath().toString();
		}
		if (filename == null) {
			filename = "version-range-maven-plugin.properties";
		}
		Properties p = new Properties();
		try {
			p.load(new FileInputStream(new File(path, filename)));
		} catch (FileNotFoundException e) {
			throw new MojoExecutionException("unable to find '" + filename + "'; path='" + path + "'", e);
		} catch (IOException e) {
			throw new MojoExecutionException("IOException while searching '" + filename + "'; path='" + path + "'", e);
		}
		

		// naja ...
		List<String> list = new ArrayList<String>();
		for (Object object : p.keySet()) {
			list.add(object.toString());
		}
		return list;
	}
}
