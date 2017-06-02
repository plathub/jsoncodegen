package de.lisaplus.atlas.builder

import de.lisaplus.atlas.interf.IModelBuilder
import de.lisaplus.atlas.model.BaseType
import de.lisaplus.atlas.model.BooleanType
import de.lisaplus.atlas.model.ComplexType
import de.lisaplus.atlas.model.DummyType
import de.lisaplus.atlas.model.IntType
import de.lisaplus.atlas.model.Model
import de.lisaplus.atlas.model.NumberType
import de.lisaplus.atlas.model.Property
import de.lisaplus.atlas.model.RefType
import de.lisaplus.atlas.model.StringType
import de.lisaplus.atlas.model.Type
import de.lisaplus.atlas.model.UnsupportedType
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static de.lisaplus.atlas.builder.helper.BuildHelper.listFromMap
import static de.lisaplus.atlas.builder.helper.BuildHelper.strFromMap
import static de.lisaplus.atlas.builder.helper.BuildHelper.string2Name

/**
 * Created by eiko on 01.06.17.
 */
class JsonSchemaBuilder implements IModelBuilder {
    /**
     * Container for all created types helps - makes reference handling easier
     */
    def createdTypes=[:]

    /**
     * builds a meta model from a model files
     * @param modelFile
     * @return
     */
    Model buildModel(File modelFile) {
        def jsonSlurper = new JsonSlurper()
        def objectModel = jsonSlurper.parse(modelFile)
        if (!objectModel['$schema']) {
            def errorMsg='model file seems to be no JSON schema'
            log.error(errorMsg)
            throw new Exception(errorMsg)
        }
        if (objectModel['definitions']) {
            // multi type schema
            return modelFromMultiTypeSchema(objectModel)
        }
        else if (objectModel['properties']) {
            // single type schema
            return modelFromSingeTypeSchema(objectModel,modelFile.getName())
        }
        else {
            def errorMsg='unknown schema structure'
            log.error(errorMsg)
            throw new Exception(errorMsg)
        }
    }

    private Model modelFromSingeTypeSchema(def objectModel, String modelFileName) {
        Model model = initModel(objectModel)
        def typeName = strFromMap(objectModel,'title')
        if (!typeName) {
            int lastDot = modelFileName.lastIndexOf('.')
            if (lastDot==-1) {
                typeName = modelFileName
            }
            else {
                typeName = modelFileName.substring(0,lastDot)
            }
        }
        typeName = string2Name(typeName)
        Type newType = new Type()
        newType.name = typeName
        newType.description = strFromMap(objectModel,'description')
        newType.properties = getProperties(objectModel,typeName)
        // TODO initialize extra stuff
        addNewType(newType,model)
        checkModelForErrors(model)
        return model
    }

    private Model modelFromMultiTypeSchema(def objectModel) {
        Model model = initModel(objectModel)
        objectModel.definitions.each { typeObj ->
            def typeName = string2Name(typeObj.key)
            Type newType = new Type()
            newType.name = typeName
            newType.description = strFromMap(typeObj.value,'description')
            newType.properties = getProperties(typeObj.value,typeName)
            // TODO  initialize extra stuff
            addNewType(newType,model)
        }
        checkModelForErrors(model)
        return model
    }

    /**
     * check if there are any errors in the model definition
     * for instance unresolved Dummytypes
     * @param model
     */
    private checkModelForErrors(def model) {
        // TODO
    }

    /**
     * wraps the append of a new type to a model, this function checks for double types
     * @param newType
     * @param model
     */
    private void addNewType(Type newType, def model) {
        def typeName = newType.name
        def alreadyCreated = createdTypes[typeName]
        if (alreadyCreated) {
            if (alreadyCreated instanceof DummyType) {
                // handle forward usage of types in declarations ... references need to be updated
                alreadyCreated.referencesToChange.each { refType ->
                    refType.type = newType
                    refType.typeName = newType.name
                }
            }
            else {
                def errorMsg = "schema contains dulplicate type: ${typeName}"
                log.error(errorMsg)
                throw new Exception(errorMsg)
            }
        }
        createdTypes[newType.name] = newType
        model.types.add(newType)
    }

    private List<Property> getProperties(def propertyParent,def parentName) {
        List<Property> propList = []
        propertyParent.properties.each { propObj ->
            def newProp = new Property()
            newProp.name = string2Name(propObj.key,false)
            newProp.description = propObj.value['description']
            newProp.type = getPropertyType(propObj.value,parentName+string2Name(propObj.key))
            propList.add(newProp)
        }
        return propList
    }

    private BaseType getPropertyType(def propObjMap,def innerTypeBaseName) {
        if (propObjMap.'$ref') {
            // reference to an external type
            return initRefType(propObjMap.'$ref')
        }
        else if (! propObjMap.type) {
            def errorMsg = "property object w/o any type: ${propObjMap}"
            log.error(errorMsg)
            throw new Exception(errorMsg)
        }
        else {
            return getBaseTypeFromString(propObjMap,innerTypeBaseName)
        }
    }

    private RefType initRefType(def refStr) {
        if (!refStr) {
            def errorMsg = "undefined refStr, so cancel init reference type"
            log.error(errorMsg)
            throw new Exception(errorMsg)
        }
        RefType refType = new RefType()
        // Examples:
        // "$ref": "#/definitions/command"
        // "$ref": "definitions.json#/address"
        // "$ref": "http: //json-schema.org/geo" - HTTP not supported (eiko)
        def localDefStrBase = '#/definitions/'
        if (refStr.startsWith(localDefStrBase)) {
            def schemaTypeName = refStr.substring(localDefStrBase.length())
            Type t = getLocalRefType(schemaTypeName)
            if (t instanceof DummyType) {
                // the needed type isn't already in the model created. later a update to the
                // right references is needed
                ((DummyType)t).referencesToChange.add(refType)
            }
            else {
                refType.type=t
                refType.typeName=t.name
            }
        }
        else {
            // "$ref": "definitions.json#/address"
            // "$ref": "http: //json-schema.org/geo" - HTTP not supported (eiko)
            // TODO init RefType
        }
        return refType
    }

    private Type getLocalRefType(def schemaTypeName) {
        // "$ref": "#/definitions/command"

        if (schemaTypeName.indexOf('/')!=-1) {
            // unsupported, something like: #/definitions/command/xxx
            def errorMsg = "unsupported local reference, types need be located under #/definitions: ${schemaTypeName}"
            log.error(errorMsg)
            throw new Exception(errorMsg)
        }
        def typeName=string2Name(schemaTypeName)
        Type alreadyCreatedType = createdTypes[typeName]
        if (alreadyCreatedType) {
            // the type is created in a earlier parsing step - fine :)
            // ... but it's possible that it is a DummyType
            return alreadyCreatedType
        }
        else {
            // the reference Points to a type that is later created - more complicated :-/
            def newDummy = new DummyType()
            createdTypes[typeName] = newDummy
            return newDummy
        }

    }

    private ComplexType initComplexType(def propertiesParent,def baseTypeName) {
        if (!propertiesParent) {
            def errorMsg = "undefined properties map, so cancel init complex type"
            log.error(errorMsg)
            throw new Exception(errorMsg)
        }
        ComplexType complexType = new ComplexType()
        Type newType = new Type()
        newType.name = baseTypeName
        newType.properties = getProperties(propertiesParent,baseTypeName)
        complexType.type = newType
        return complexType
    }

    private BaseType getBaseTypeFromString(def propObjMap, def innerTypeBaseName, def isArrayAllowed=true) {
        switch (propObjMap.type) {
            case 'string':
                return new StringType()
            case 'integer':
                return new IntType()
            case 'number':
                return new NumberType()
            case 'boolean':
                return new BooleanType()
            case 'object':
                if (propObjMap.patternProperties) {
                    log.warn("unsupported 'patternProperties' entry found")
                    return new UnsupportedType()
                }
                else
                    return initComplexType(propObjMap,innerTypeBaseName)
            case 'array':
                if (!isArrayAllowed) {
                    def errorMsg = "detect not allowed sub array type"
                    log.error(errorMsg)
                    throw new Exception(errorMsg)
                }
                if (propObjMap.items.type) {
                    BaseType ret = getBaseTypeFromString(propObjMap.items,innerTypeBaseName+'Item',false)
                    ret.isArray = true
                    return ret
                }
                else if (propObjMap.items['$ref']) {
                    BaseType ret = initRefType(propObjMap.items['$ref'])
                    ret.isArray = true
                    return ret
                }
                else {
                    def errorMsg = "unknown array type"
                    log.error(errorMsg)
                    throw new Exception(errorMsg)
                }
            default:
                def errorMsg = "property with unknown type: ${propObjMap.type}"
                log.error(errorMsg)
                throw new Exception(errorMsg)
        }
    }

    private Model initModel(def objectModel) {
        Model model = new Model()
        model.title = strFromMap(objectModel,'title')
        model.description = strFromMap(objectModel,'description')
        return model
    }

    private static final Logger log=LoggerFactory.getLogger(JsonSchemaBuilder.class);
}
