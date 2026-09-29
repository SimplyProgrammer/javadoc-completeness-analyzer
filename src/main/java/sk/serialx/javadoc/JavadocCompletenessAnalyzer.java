package sk.serialx.javadoc;

import static java.lang.System.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
import com.github.javaparser.javadoc.JavadocBlockTag.Type;

/**
 * Simple analyzer for project-defined Javadoc completeness.
 *
 * The analyzer recursively scans a directory, including Maven multimodule
 * and nested projects. It excludes build/output/VCS directories and test
 * sources by default.
 */
public final class JavadocCompletenessAnalyzer {
	private JavadocCompletenessAnalyzer() {}
	
	public static final Set<String> EXCLUDED_DIRS = Set.of(
		".git", ".idea", ".settings", "target", "build", "out",
		"bin", "node_modules"
	);

	public static void main(String[] args) throws Exception 
	{
		Config config = Config.parse(args);
		if (config.help) 
		{
			printHelp();
			return;
		}

		if (!Files.isDirectory(config.root))
			throw new IllegalArgumentException("Not a directory: " + config.root);

		List<Path> javaFiles = collectJavaFiles(config.root, config.includeTests);
		List<MetricRow> rows = new ArrayList<>();
		List<String> parseErrors = new ArrayList<>();

		for (Path file : javaFiles) {
			try {
				CompilationUnit cu = StaticJavaParser.parse(file);
				String module = findNearestModule(file, config.root);

				for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class))
					rows.add(analyzeClass(type, file, module, config.root));
				for (EnumDeclaration type : cu.findAll(EnumDeclaration.class))
					rows.add(analyzeClass(type, file, module, config.root));
				for (RecordDeclaration type : cu.findAll(RecordDeclaration.class))
					rows.add(analyzeClass(type, file, module, config.root));
				for (AnnotationDeclaration type : cu.findAll(AnnotationDeclaration.class))
					rows.add(analyzeClass(type, file, module, config.root));
				for (MethodDeclaration method : cu.findAll(MethodDeclaration.class))
				{
					if (config.ignoreOverriddenMethods && method.isAnnotationPresent("Override"))
						continue;
					
					rows.add(analyzeMethod(method, file, module, config.root, config));
				}
			} catch (RuntimeException e) {
				parseErrors.add(rel(config.root, file) + ": " + e.getMessage());
			}
		}

		writeCsv(config.output, rows);
		printSummary(config.root, javaFiles.size(), rows, parseErrors, config);
	}

	public static MetricRow analyzeClass(Node node, Path file, String module, Path root)
	{
		Optional<Javadoc> jd = getJavadoc(node);

		boolean description = jd.map(JavadocCompletenessAnalyzer::hasDescription).orElse(false);
		boolean since = hasTag(jd, Type.SINCE);
		boolean author = hasTag(jd, Type.AUTHOR);

		int achieved = bool(description) + bool(since) + bool(author);
		int required = 3;

		return new MetricRow(
			module, rel(root, file), line(node), "CLASS",
			nodeName(node), required, achieved,
			description, countTag(jd, Type.PARAM),
			hasTag(jd, Type.RETURN), since, author,
			false, false
		);
	}

	public static MetricRow analyzeMethod(MethodDeclaration method, Path file, String module, Path root, Config config)
	{
		Optional<Javadoc> jd = method.getJavadoc();

		int required = method.getParameters().size() + 1; // @param(s) + @since
		int achieved = 0;

		int paramPresent = 0;
		if (jd.isPresent()) {
			Set<String> documentedParams = new HashSet<>();
			for (JavadocBlockTag tag : jd.get().getBlockTags()) 
			{
				if (tag.getType() == Type.PARAM)
					tag.getName().ifPresent(name -> documentedParams.add(normalizeParamName(name)));
			}

			for (var p : method.getParameters()) 
			{
				String name = normalizeParamName(p.getNameAsString());
				if (documentedParams.contains(name))
					paramPresent++;
			}

			achieved += paramPresent;
		}

		boolean needsReturn = !method.getType().isVoidType();
		boolean explicitReturn = hasTag(jd, Type.RETURN);
		boolean descriptionFallbackReturn = config.descriptionCountsAsReturn && jd.isPresent() && hasDescription(jd.get()) && needsReturn;

		boolean returnPresent = explicitReturn || descriptionFallbackReturn;

		// @return is a required metric component for non-void methods.
		// Void methods do not receive a return requirement.
		if (needsReturn)
		{
			required++;
			if (returnPresent)
				achieved++;
		}

		boolean since = hasTag(jd, Type.SINCE);
		if (since)
			achieved++;

		return new MetricRow(
			module, rel(root, file), line(method), "METHOD",
			method.getNameAsString(), required, achieved,
			jd.map(JavadocCompletenessAnalyzer::hasDescription).orElse(false),
			paramPresent, returnPresent, since, false,
			explicitReturn, descriptionFallbackReturn
		);
	}

	public static Optional<Javadoc> getJavadoc(Node node) {
		if (node instanceof ClassOrInterfaceDeclaration c) return c.getJavadoc();
		if (node instanceof EnumDeclaration e) return e.getJavadoc();
		if (node instanceof RecordDeclaration r) return r.getJavadoc();
		if (node instanceof AnnotationDeclaration a) return a.getJavadoc();
		return Optional.empty();
	}

	public static boolean hasDescription(Javadoc jd)
	{
		return jd.getDescription() != null && !jd.getDescription().toText().trim().isEmpty();
	}

	public static boolean hasTag(Optional<Javadoc> jd, Type type)
	{
		return jd.map(j -> j.getBlockTags().stream()
			.anyMatch(tag -> tag.getType() == type && hasTagContent(tag)))
			.orElse(false);
	}

	public static boolean hasTagContent(JavadocBlockTag tag)
	{
		return tag.getContent() != null && !tag.getContent().toText().trim().isEmpty();
	}

	public static int countTag(Optional<Javadoc> jd, Type type)
	{
		return jd.map(j -> (int) j.getBlockTags().stream()
			.filter(tag -> tag.getType() == type && hasTagContent(tag))
			.count()).orElse(0);
	}

	public static String normalizeParamName(String name)
	{
		return name.replace("<", "").replace(">", "").trim(); // JavaParser may represent type-parameter tags as "<T>".
	}

	public static int bool(boolean b)
	{
		return b ? 1 : 0;
	}

	public static int line(Node node)
	{
		return node.getRange().map(r -> r.begin.line).orElse(-1);
	}

	public static String nodeName(Node node)
	{
		if (node instanceof ClassOrInterfaceDeclaration c) return c.getNameAsString();
		if (node instanceof EnumDeclaration e) return e.getNameAsString();
		if (node instanceof RecordDeclaration r) return r.getNameAsString();
		if (node instanceof AnnotationDeclaration a) return a.getNameAsString();
		return "<unknown>";
	}

	public static List<Path> collectJavaFiles(Path root, boolean includeTests) throws IOException
	{
		try (Stream<Path> stream = Files.walk(root)) 
		{
			return stream
					.filter(Files::isRegularFile)
					.filter(p -> p.toString().endsWith(".java"))
					.filter(p -> !isExcluded(p, root))
					.filter(p -> includeTests || !isTestSource(p))
					.sorted()
					.toList();
		}
	}

	public static boolean isExcluded(Path path, Path root)
	{
		Path relative = root.relativize(path);
		for (Path part : relative)
			if (EXCLUDED_DIRS.contains(part.toString()))
				return true;
		return false;
	}

	public static boolean isTestSource(Path path)
	{
		String normalized = path.toString().replace('\\', '/');
		return normalized.contains("/src/test/")
				|| normalized.contains("/src/it/")
				|| normalized.contains("/test/java/");
	}

	public static String findNearestModule(Path file, Path root)
	{
		Path current = file.getParent();
		while (current != null && current.startsWith(root)) 
		{
			if (Files.exists(current.resolve("pom.xml"))) 
			{
				Path rel = root.relativize(current);
				return rel.toString().isEmpty() ? "." : rel.toString().replace('\\', '/');
			}
			current = current.getParent();
		}
		return ".";
	}

	public static String rel(Path root, Path path)
	{
		return root.relativize(path).toString().replace('\\', '/');
	}

	public static void writeCsv(Path output, List<MetricRow> rows) throws IOException
	{
		if (output.getParent() != null)
			Files.createDirectories(output.getParent());

		try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output))) 
		{
			out.println("module,file,line,type,element,required,achieved,description,param_tags,return_tag,since_tag,author_tag,explicit_return,description_return_fallback,percentage");

			for (MetricRow r : rows) {
				out.printf(Locale.ROOT,
						"%s,%s,%d,%s,%s,%d,%d,%s,%d,%s,%s,%s,%s,%s,%.2f%n",
						csv(r.module), csv(r.file), r.line, r.type, csv(r.element),
						r.required, r.achieved, r.description,
						r.paramTags, r.returnTag, r.sinceTag, r.authorTag,
						r.explicitReturn, r.descriptionReturnFallback,
						r.percentage());
			}
		}
	}

	public static void printSummary(Path root, int javaFileCount, List<MetricRow> rows, List<String> parseErrors, Config config)
	{

		List<MetricRow> classes = rows.stream().filter(r -> r.type.equals("CLASS")).toList();
		List<MetricRow> methods = rows.stream().filter(r -> r.type.equals("METHOD")).toList();

		out.println("Javadoc Completeness Analyzer");
		out.println("-----------------------------");
		out.println("Root: " + root.toAbsolutePath());
		out.println("Java files: " + javaFileCount);
		out.println("Classes: " + classes.size());
		out.println("Methods: " + methods.size());
		out.println("Tests included: " + config.includeTests);
		out.println("Description counts as @return fallback: " + config.descriptionCountsAsReturn);
		out.printf(Locale.ROOT, "Class completeness: %.2f%%%n", weightedPercentage(classes));
		out.printf(Locale.ROOT, "Method completeness: %.2f%%%n", weightedPercentage(methods));
		out.printf(Locale.ROOT, "Overall completeness: %.2f%%%n", weightedPercentage(rows));
		out.println("CSV: " + config.output.toAbsolutePath());

		if (!parseErrors.isEmpty())
		{
			out.println();
			out.println("Parse errors: " + parseErrors.size());
			parseErrors.forEach(e -> out.println("  " + e));
		}
	}

	public static double weightedPercentage(List<MetricRow> rows)
	{
		int required = rows.stream().mapToInt(r -> r.required).sum();
		int achieved = rows.stream().mapToInt(r -> r.achieved).sum();
		return required == 0 ? 100.0 : (100.0 * achieved / required);
	}

	public static String csv(String s)
	{
		String escaped = s.replace("\"", "\"\"");
		return "\"" + escaped + "\"";
	}

	private record MetricRow(
			String module,
			String file,
			int line,
			String type,
			String element,
			int required,
			int achieved,
			boolean description,
			int paramTags,
			boolean returnTag,
			boolean sinceTag,
			boolean authorTag,
			boolean explicitReturn,
			boolean descriptionReturnFallback) {

		double percentage() 
		{
			return required == 0 ? 100.0 : 100.0 * achieved / required;
		}
	}

	public static final class Config 
	{
		final Path root;
		final Path output;
		final boolean includeTests;
		final boolean descriptionCountsAsReturn;
		final boolean ignoreOverriddenMethods;
		final boolean help;

		private Config(Path root, Path output, boolean includeTests, boolean descriptionCountsAsReturn,  boolean ignoreOverriddenMethods, boolean help)
		{
			this.root = root;
			this.output = output;
			this.includeTests = includeTests;
			this.descriptionCountsAsReturn = descriptionCountsAsReturn;
			this.help = help;
			this.ignoreOverriddenMethods = ignoreOverriddenMethods;
		}

		static Config parse(String[] args) 
		{
			Path root = null;
			Path output = Path.of("javadoc-completeness.csv");
			boolean includeTests = false, descriptionCountsAsReturn = true, ignoreOverriddenMethods = true;

			for (int i = 0; i < args.length; i++) {
				switch (args[i]) {
					case "-h", "--help" -> {
						return new Config(Path.of("."), output, includeTests, descriptionCountsAsReturn, ignoreOverriddenMethods, true);
					}
					case "--root" -> root = Path.of(requireValue(args, ++i, "--root"));
					case "--output" -> output = Path.of(requireValue(args, ++i, "--output"));
					case "--include-tests" -> includeTests = true;
					case "--strict-return" -> descriptionCountsAsReturn = false;
					case "--include-overridden" -> ignoreOverriddenMethods = false;
					default -> {
						if (root == null && !args[i].startsWith("-"))
							root = Path.of(args[i]);
						else
							throw new IllegalArgumentException("Unknown argument: " + args[i]);
					}
				}
			}

			if (root == null)
				root = Path.of(".");

			return new Config(root.toAbsolutePath().normalize(), output, includeTests, descriptionCountsAsReturn, ignoreOverriddenMethods, false);
		}

		public static String requireValue(String[] args, int index, String option) 
		{
			if (index >= args.length)
				throw new IllegalArgumentException("Missing value for " + option);
			return args[index];
		}
	}

	public static void printHelp() 
	{
		out.println("""
				Javadoc Completeness Analyzer

				Usage:
				  java -jar javadoc-completeness-analyzer.jar <project-root>
				  java -jar javadoc-completeness-analyzer.jar --root <dir> [options]

				Options:
				  --output <file>     CSV output (default: javadoc-completeness.csv)
				  --include-tests     Include src/test and similar test source trees
				  --strict-return     Do not treat a non-empty Javadoc description as
									  an implicit @return when @return is missing
				  -h, --help          Show this help

				Default metric:
				  CLASS  = description + @since + @author
				  METHOD = one @param per declared parameter + @return for non-void
						   methods + @since

				By default, a non-empty method description can satisfy the @return
				requirement when the explicit @return tag is absent. This is a
				deliberate project-specific rule, not a claim about standard Javadoc.

				The analyzer recursively scans nested/multimodule projects and
				excludes common build/VCS directories.
				""");
	}
}
