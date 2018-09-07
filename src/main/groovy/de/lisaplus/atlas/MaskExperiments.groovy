package de.lisaplus.atlas

import de.lisaplus.atlas.builder.JsonSchemaBuilder
import de.lisaplus.atlas.codegen.GeneratorBase
import de.lisaplus.atlas.interf.IModelBuilder
import de.lisaplus.atlas.model.Model
import de.lisaplus.atlas.model.Type
import de.lisaplus.atlas.model.Property

class MaskExperiments {

    // TODO read model
    // TODO extract type
    // TODO traversal the model and
    // TODO        generate all methods chechXXXExists(JunctionContact target) OR
    // TODO        generate all methods chechXXXExists(JunctionContactJoined target)
    // TODO        see usage of extraParam.joinedRun in codeGenTemplates\java\mongodb_beans_tests.txt
    // TODO        maintain three stacks propStack, propIsArrayStack and propIsCollectionStack
    // TODO        generate methods List<XXX> getXXX(JunctionContactJoined target) according to propIsCollectionStack
    // TODO        Add cases to switch of method mask(JunctionContactJoined target, PojoMask mask)
    // TODO        (Collect list of lines and write out after loop is done or write while looping?)
    // TODO        Run multiple loops:
    // TODO        Loop1: writer method mask(JunctionContactJoined target, PojoMask mask)
    // TODO        Loop2: write methods chechXXXExists(JunctionContact target) and getXXX(JunctionContactJoined target) below method mask

    String typeName
    Model model

    static main(args) {

        def base = 'C:\\Entwicklung\\lisa-junction-server\\models\\models-lisa-server\\model\\'
        def modelPath = args.length == 0 ?
                base + 'junction.json'
                // base + 'shared\\geo_point.json'
                : args[0]
        def type = args.length > 0 ?
                args[1]
                : 'JunctionContact'
        def joined = args.length > 1 ?
                Boolean.valueOf(args[2])
                : true

        def maskExp = new MaskExperiments(type, modelPath)
        maskExp.execute(joined)

    }

    MaskExperiments(typeName, modelPath) {
        this.typeName = typeName
        this.model = readModel(modelPath)
    }

    private Model readModel(String modelPath) {
        def modelFile = new File(modelPath)
        IModelBuilder builder = new JsonSchemaBuilder()
        return builder.buildModel(modelFile)
    }

    /** This stack holds the property (names) visited while traversing the object hierarchy.*/
    List propStack
    /** Indicates that this property is an array. This sack is build while traversing the object hierarchy. */
    List propIsArrayStack
    /**
     *  Indicates that this property or any of its parents was an array and that we therefore have to process an collection.
     *  This sack is build while traversing the object hierarchy.
     */
    List propIsCollectionStack
    Map data
    boolean joined
    String targetType

    void execute(boolean joined) {
        this.joined = joined;
        GeneratorBase generator = new DummyGenerator()
        data = generator.createTemplateDataMap(model)
        Type type = data.model.types.find {type -> type.name == typeName}
        targetType = type.name + (joined ? 'Joined' : '')

        // First loop: method mask
        propStack = []; propIsArrayStack = []
        // avoid extra case for handling empty stack!
        propIsCollectionStack = [false]

        println '''    public static void mask(JunctionContactJoined target, PojoMask mask) {
        for (final String key : mask.hiddenKeys()) {
            switch(key) {'''

        printCaseForType(type)

        println '''            }
        }
    }'''
        // Second loop: method checkXXXExists()

        propStack = []; propIsArrayStack = []
        // avoid extra case for handling empty stack!
        propIsCollectionStack = [false]
        printCheckExistsForType(type)

        // Third loop: method getXXX()

        propStack = []; propIsArrayStack = []
        // avoid extra case for handling empty stack!
        propIsCollectionStack = [false]
        printGetForType(type)

    }

    /** Simple setXXX(null) without any array types in-between -> no for(xxx item : getXXX()) item.setYYY(null) */
    void printCaseSimple(Property prop) {
        def lines
        if (propStack.isEmpty()) {
            lines = /            case "${prop.name}":
                target.set${data.upperCamelCase.call(prop.name)}(null);
                break;/
        } else {
            // FIXME use methods getXXX() where necessary.
            // Either examine propIsArrayStack or fill and evaluate propIsCollectionStack!
            def checkMethodPart = propStack.collect{ data.upperCamelCase.call(it) }.join('')       // e.g. AddressPersonsContact
            def getChain = propStack.collect{ "get${data.upperCamelCase.call(it)}" } .join('().')  // e.g. getObjectBase().getGis().getArea
            propStack.add(prop.name)
            def key = propStack.join('.')
            propStack.pop()
            lines = /            case "${key}":
                if (chech${checkMethodPart}Exists(target)) {
                    target.${getChain}().set${data.upperCamelCase.call(prop.name)}(null);
                }
                break;/
        }
        println lines
    }

    void printCaseComplex(Property property) {
        Type type = property.type.type
        printCaseForType(type)
    }

    void printCaseJoined(Property property) {
        Type type = property.implicitRef.type
        printCaseForType(type)
    }

    /**
     * Prints the case statements for a certain type, calls itself recursively for reference and complex types!
     * @param type The type to process
     */
    void printCaseForType(Type type) {
        data.filterProps.call(type, [refComplex:false]).each { Property prop ->
            printCaseSimple(prop)
        }

//        type.properties.findAll { prop -> return prop.isRefTypeOrComplexType() }.each { prop ->
        data.filterProps.call(type, [refComplex:true]).each { Property prop ->
            // recursive call!
            propStack.add(prop.name)
//            println "debug ${prop.name}: ${prop.type}"
//            if (!prop.type) {
//                println 'why is it missing, need isArray!'
//            }
            propIsArrayStack.add(prop.type.isArray)
            propIsCollectionStack.add('TODO')
            printCaseComplex(prop)
            propStack.pop()
            propIsArrayStack.pop()
            propIsCollectionStack.pop()
        }

        if (!joined) {
            return
        }
        data.filterProps.call(type, [prepLookup:true, implRefIsRef:true]).each { Property prop ->
            // recursive call!
            propStack.add(prop.name)
//            println "debug ${prop.name}: ${prop.type}"
//            if (!prop.type) {
//                println 'why is it missing, need isArray!'
//            }
            propIsArrayStack.add(prop.type.isArray)
            propIsCollectionStack.add('TODO')
            printCaseJoined(prop)
            propStack.pop()
            propIsArrayStack.pop()
            propIsCollectionStack.pop()
        }
    }

    /**
     * Prints the methods checkXXX(target) for a certain type, calls itself recursively for reference and complex types!
     * @param type The type to process
     */
    void printCheckExistsForType(Type type) {
        if (!propStack.isEmpty()) {
            /*
                private static boolean checkObjectBaseGisArea(JunctionNumberJoined target) {
                    return target.getObjectBase() != null
                            && target.getObjectBase().getGis() != null
                            && target.getObjectBase().getGis().getArea() != null;
                }
             */
            def checkMethodPart = propStack.collect{ data.upperCamelCase.call(it) }.join('')       // e.g. AddressPersonsContact
            // create longest getter call chain and then process it from one to all elements.
            List lines = []
            List getCalls = propStack.collect { "get${data.upperCamelCase.call(it)}()"}
            for (int i = 0; i < getCalls.size(); i++) {
                def cond = getCalls.subList(0, i+1).join('.')
                lines.add("target.${cond} != null")
            }
            def conditions = lines.join('\n                && ')
            println "\n    private static boolean check${checkMethodPart}Exists(${targetType} target) {\n        return ${conditions};\n    }"
        }

        data.filterProps.call(type, [refComplex:true]).each { Property prop ->
//        type.properties.findAll { prop -> return prop.isRefTypeOrComplexType() }.each { prop ->
            // recursive call!
            propStack.add(prop.name)
            propIsArrayStack.add(prop.type.isArray)
            propIsCollectionStack.add('TODO2')
            printCheckExistsForType(prop.type.type)
            propStack.pop()
            propIsArrayStack.pop()
            propIsCollectionStack.pop()
        }

        if (joined) {
            data.filterProps.call(type, [prepLookup:true, implRefIsRef:true]).each { Property prop ->
                // recursive call!
                propStack.add(prop.name)
                propIsArrayStack.add(prop.type.isArray)
                propIsCollectionStack.add('TODO2')
                printCheckExistsForType(prop.implicitRef.type)
                propStack.pop()
                propIsArrayStack.pop()
                propIsCollectionStack.pop()
            }
        }
    }

    /**
     * Prints the methods checkXXX(target) for a certain type, calls itself recursively for reference and complex types!
     * @param type The type to process
     */
    void printGetForType(Type type) {
        // FIXME generate methods getXXX() only when actually necessary.
        // Either examine propIsArrayStack or fill and evaluate propIsCollectionStack!
        if (!propStack.isEmpty()) {
            // Example for key address.persons.contact where persons is the only array type
            // In case of multiple array types use .flatMap() for 2. to last array type!
            /*
                private static List<ContactData> getContactData(JunctionContactJoined target) {
                    if (checkAddressPersonsContactExists(target)) {
                        return target.getAddress().getPersons().stream()
                                                               .map(p -> p.getContact())
                                                               .collect(Collectors.toList());
                    }
                    return Collections.emptyList();
                }
             */
            def checkMethodPart = propStack.subList(0, propStack.size()).collect { data.upperCamelCase.call(it) }.join('') // e.g. AddressPersonsContact

            // iterate through propStack and propIsArrayStack
            // Before first array type is encountered, add getter calls
            // When first array type is encountered, add .stream() and switch mode to .map(...)
            // Whenever another array type is encountered, use .flatMap(...) instead of .map(...)
            List parts = []
            boolean useGetter = true;
            for (int i = 0; i < propStack.size(); i++) {
                def currUpper = data.upperCamelCase.call(propStack[i])
                if (useGetter) {
                    // getXXX()
                    parts.add("get${currUpper}()")
                } else {
                    def parentProp = propStack[i-1].take(1)
                    if (propIsArrayStack[i]) {
                        // flatMap(), e.g. flatMap(contact -> contact.getEmail().stream())
                        parts.add("flatMap(${parentProp} -> ${parentProp}.get${currUpper}().stream())")
                    } else {
                        // map(), e.g. map(person -> person.getContact())
                        parts.add("map(${parentProp} -> ${parentProp}.get${currUpper}()")
                    }
                }
                if (useGetter && propIsArrayStack[i]) {
                    parts.add("stream()")
                    useGetter = false
                }
            }
            parts.add('collect(Collectors.toList())')

            // If the very last entry is the first array type, then these is no need to appending
            // .stream() and .collect(Collectors.toList()) -> just discard these two entries!
            if (parts[parts.size()-2] == "stream()") {
                parts = parts.subList(0, parts.size() - 2)
            }
            def retType = data.upperCamelCase.call(type.name)
            def stream = parts[0] + parts.subList(1,parts.size()).collect {"\n                    .$it"}.join('')
                    println """
    private static List<${retType}> get${retType}(${targetType} target) {
        if (check${checkMethodPart}Exists(target) {
            return target.${stream};
        }
        return Collections.emptyList();
    }"""
        }

        data.filterProps.call(type, [refComplex:true]).each { Property prop ->
//        type.properties.findAll { prop -> return prop.isRefTypeOrComplexType() }.each { prop ->
            // recursive call!
            propStack.add(prop.name)
            propIsArrayStack.add(prop.type.isArray)
            propIsCollectionStack.add('TODO3')
            printGetForType(prop.type.type)
            propStack.pop()
            propIsArrayStack.pop()
            propIsCollectionStack.pop()
        }

        if (joined) {
            data.filterProps.call(type, [prepLookup:true, implRefIsRef:true]).each { Property prop ->
                // recursive call!
                propStack.add(prop.name)
                propIsArrayStack.add(prop.type.isArray)
                propIsCollectionStack.add('TODO3')
                printGetForType(prop.implicitRef.type)
                propStack.pop()
                propIsArrayStack.pop()
                propIsCollectionStack.pop()
            }
        }
    }
}
