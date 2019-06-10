package fr.emse.ci.sparqlext.bgprewriting;

import fr.emse.ci.sparqlext.SPARQLExt;
import fr.emse.ci.sparqlext.generate.engine.PlanFactory;
import fr.emse.ci.sparqlext.generate.engine.RootPlan;
import fr.emse.ci.sparqlext.query.SPARQLExtQuery;
import fr.emse.ci.sparqlext.stream.LocationMapperAccept;
import fr.emse.ci.sparqlext.stream.LocatorFileAccept;
import fr.emse.ci.sparqlext.stream.SPARQLExtStreamManager;
import java.io.File;
import java.io.FileInputStream;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.apache.commons.io.IOUtils;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.Syntax;
import org.apache.jena.rdf.model.AnonId;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.syntax.ElementPathBlock;
import org.apache.jena.sparql.syntax.ElementTriplesBlock;
import org.apache.jena.sparql.util.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransformBGP {

    private static final Logger LOG = LoggerFactory.getLogger(TransformBGP.class);

    public static final String BASE = "http://example.org/";
    public static final String SELECT_PATH = "select.rq";
    public static final String TRANSFORMATION_PATH = "main.rqg";

    /**
     * Transform SELECT queries containing just a Basic Graph Pattern into
     * another SELECT query, according to a SPARQL-Generate transformation.
     *
     * First argument is path to a directory that contains:
     *
     * - select.rq the select query to be transformed. - main.rqg the main
     * SPARQL-Generate query.
     *
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {

        if (args.length != 1) {
            throw new RuntimeException("expecting one arguments");
        }
        String dir = args[0];
        File dirFile = new File(dir);
        Path dirPath = Paths.get(dirFile.toURI());

        // initialize stream manager
        LocatorFileAccept locator = new LocatorFileAccept(dirFile.toURI().getPath());
        LocationMapperAccept mapper = new LocationMapperAccept();
        SPARQLExtStreamManager sm = SPARQLExtStreamManager.makeStreamManager(locator);
        sm.setLocationMapper(mapper);

        Files.walk(dirPath)
                .filter((p) -> {
                    return p.toFile().isFile();
                })
                .forEach((p) -> {
                    String relativePath = dirPath.relativize(p).toString();
                    String url = BASE + relativePath.replace("\\", "/");
                    mapper.addAltEntry(url, p.toString());
                });


        File selectQueryFile = new File(dirFile, SELECT_PATH);
        String selectQueryString = IOUtils.toString(new FileInputStream(selectQueryFile), StandardCharsets.UTF_8);
        Query selectQuery = QueryFactory.create(selectQueryString, BASE, Syntax.syntaxSPARQL_11);

        Element e =  selectQuery.getQueryPattern();
        if(!(e instanceof ElementGroup)) {
            throw new RuntimeException("expected element group query pattern. usually it is");
        }
        ElementGroup group = (ElementGroup)e;
        if(group.size()!=1 || !(group.get(0) instanceof ElementPathBlock)) {
            throw new RuntimeException("only work for one element path block");
        }
        ElementPathBlock elb = (ElementPathBlock)group.get(0);
        
        String falseVariableBase = BASE + UUID.randomUUID().toString().substring(0,6)+ "#";
        Model input = replaceVariablesByURIs(elb, falseVariableBase);
        

        
        File transformationFile = new File(dirFile, TRANSFORMATION_PATH);
        String transformationString = IOUtils.toString(new FileInputStream(transformationFile), StandardCharsets.UTF_8);
        SPARQLExtQuery transformationQuery = (SPARQLExtQuery) QueryFactory.create(transformationString, BASE, SPARQLExt.SYNTAX);
        RootPlan plan = PlanFactory.create(transformationQuery);
        Context context = SPARQLExt.createContext(sm);
        Model output = plan.execGenerate(input, context);
        
        ElementTriplesBlock outputBGP = replaceURIsByVariables(output, falseVariableBase);
        group = new ElementGroup();
        group.addElement(outputBGP);
        selectQuery.setQueryPattern(group);
        
        System.out.println("output query is \n\n" + selectQuery.toString());
    }

    private static Model replaceVariablesByURIs(ElementPathBlock elb, String falseVariableBase) {
        final Model model = ModelFactory.createDefaultModel();
        elb.getPattern().forEach((pb)->{
            model.add(makeStatement(pb.asTriple(), model, falseVariableBase));
        });
        return model;
    }
    private static RDFNode makeRDFNode(Node node, Model model, String falseVariableBase) {
        if(node.isConcrete()) {
            return model.asRDFNode(node);
        } else if(node.isVariable()) {
            String uri = falseVariableBase + ((Var)node).getVarName();
            return model.getResource(uri);
        } else {
            throw new RuntimeException("did not expect this to happen");
        }
    }
    
    private static Resource makeResource(Node node, Model model, String falseVariableBase) {
        if(node.isURI()) {
            return model.getResource(node.getURI());
        } else if(node.isBlank()) {
            AnonId id = AnonId.create(node.getBlankNodeLabel());
            return model.createResource(id);
        } else if(node.isVariable()) {
            String uri = falseVariableBase + ((Var)node).getVarName();
            return model.getResource(uri);
        } else {
            throw new RuntimeException("did not expect this to happen");
        }
    }
    private static Property makeProperty(Node node, Model model, String falseVariableBase) {
        if(node.isURI()) {
            return model.getProperty(node.getURI());
        } else if(node.isVariable()) {
            String uri = falseVariableBase + ((Var)node).getVarName();
            return model.getProperty(uri);
        } else {
            throw new RuntimeException("did not expect this to happen");
        }
    }

    private static Statement makeStatement(Triple triple, Model model, String falseVariableBase) {
        Resource s = makeResource(triple.getSubject(), model, falseVariableBase);
        Property p = makeProperty(triple.getPredicate(), model, falseVariableBase);
        RDFNode o = makeRDFNode(triple.getObject(), model, falseVariableBase);
        return model.createStatement(s, p, o);
    }

    private static ElementTriplesBlock replaceURIsByVariables(Model model, String falseVariableBase) {
        BasicPattern bgp = new BasicPattern();
        StmtIterator it = model.listStatements();
        while(it.hasNext()) {
            bgp.add(makeTriple(it.nextStatement(), falseVariableBase));
        }
        return new ElementTriplesBlock(bgp);
    }

    private static Triple makeTriple(Statement statement, String falseVariableBase) {
        Node s = makeNode(statement.getSubject(), falseVariableBase);
        Node p = makeNode(statement.getPredicate(), falseVariableBase);
        Node o = makeNode(statement.getObject(), falseVariableBase);
        return new Triple(s, p, o);
    }
    
    private static Node makeNode(RDFNode node, String falseVariableBase) {
        Node n;
        if(node.isURIResource() && node.asResource().getURI().startsWith(falseVariableBase)) {
            String varName = node.asResource().getURI().substring(falseVariableBase.length());
            return Var.alloc(varName);
        } else {
            return node.asNode();
        }
    }
    
}
