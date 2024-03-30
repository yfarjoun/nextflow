/*
 * Copyright 2013-2024, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.script

import java.nio.file.Path
import java.nio.file.Paths

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowWriteChannel
import nextflow.NF
import nextflow.exception.MissingProcessException
import nextflow.exception.MissingValueException
import nextflow.exception.ScriptRuntimeException
import nextflow.extension.CH
import nextflow.extension.IntoTopicOp
import nextflow.extension.PublishOp
/**
 * Models a script workflow component
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class WorkflowDef extends BindableDef implements ChainableDef, IterableDef, ExecutionContext {

    private String name

    private BodyDef body

    private List<String> declaredInputs

    private List<String> declaredOutputs

    private Set<String> variableNames

    private BaseScript owner

    // -- following attributes are mutable and instance dependent
    // -- therefore should not be cloned

    private ChannelOut output

    private WorkflowBinding binding

    private Closure publisher

    WorkflowDef(BaseScript owner, Closure<BodyDef> rawBody, String name=null) {
        this.owner = owner
        this.name = name
        // invoke the body resolving in/out params
        final copy = (Closure<BodyDef>)rawBody.clone()
        final resolver = new WorkflowParamsDsl()
        copy.setResolveStrategy(Closure.DELEGATE_FIRST)
        copy.setDelegate(resolver)
        this.body = copy.call()
        // now it can access the parameters
        this.declaredInputs = new ArrayList<>(resolver.getTakes().keySet())
        this.declaredOutputs = new ArrayList<>(resolver.getEmits().keySet())
        this.variableNames = getVarNames0()
    }

    /* ONLY FOR TESTING PURPOSE */
    protected WorkflowDef() {}

    void setPublisher(Closure publisher) {
        this.publisher = publisher
    }

    WorkflowDef clone() {
        final copy = (WorkflowDef)super.clone()
        copy.@body = body.clone()
        return copy
    }

    WorkflowDef cloneWithName(String name) {
        def result = clone()
        result.@name = name
        return result
    }

    BaseScript getOwner() { owner }

    String getName() { name }

    WorkflowBinding getBinding() { binding }

    ChannelOut getOut() {
        if( output==null )
            throw new ScriptRuntimeException("Access to '${name}.out' is undefined since the workflow '$name' has not been invoked before accessing the output attribute")
        if( output.size()==0 )
            throw new ScriptRuntimeException("Access to '${name}.out' is undefined since the workflow '$name' doesn't declare any output")

        return output
    }

    @PackageScope BodyDef getBody() { body }

    @PackageScope List<String> getDeclaredInputs() { declaredInputs }

    @PackageScope List<String> getDeclaredOutputs() { declaredOutputs }

    @PackageScope Map<String,Map> getDeclaredPublish() { declaredPublish }

    @PackageScope String getSource() { body.source }

    @PackageScope List<String> getDeclaredVariables() { new ArrayList<String>(variableNames) }

    String getType() { 'workflow' }

    private Set<String> getVarNames0() {
        def variableNames = body.getValNames()
        if( variableNames ) {
            Set<String> declaredNames = []
            declaredNames.addAll( declaredInputs )
            if( declaredNames )
                variableNames = variableNames - declaredNames
        }
        return variableNames
    }


    protected void collectInputs(Binding context, Object[] args) {
        final params = ChannelOut.spread(args)
        if( params.size() != declaredInputs.size() ) {
            final prefix = name ? "Workflow `$name`" : "Main workflow"
            throw new IllegalArgumentException("$prefix declares ${declaredInputs.size()} input channels but ${params.size()} were given")
        }

        // attach declared inputs with the invocation arguments
        for( int i=0; i< declaredInputs.size(); i++ ) {
            final name = declaredInputs[i]
            context.setProperty( name, params[i] )
        }
    }

    protected ChannelOut collectOutputs(List<String> emissions) {
        // make sure feedback channel cardinality matches
        if( feedbackChannels && feedbackChannels.size() != emissions.size() )
            throw new ScriptRuntimeException("Workflow `$name` inputs and outputs do not have the same cardinality - Feedback loop is not supported"  )

        final channels = new LinkedHashMap<String, DataflowWriteChannel>(emissions.size())
        for( int i=0; i<emissions.size(); i++ ) {
            final targetName = emissions[i]
            if( !binding.hasVariable(targetName) )
                throw new MissingValueException("Missing workflow output parameter: $targetName")
            final obj = binding.getVariable(targetName)

            if( CH.isChannel(obj) ) {
                channels.put(targetName, target(i, obj))
            }

            else if( obj instanceof ChannelOut ) {
                if( obj.size()>1 )
                    throw new IllegalArgumentException("Cannot emit a multi-channel output: $targetName")
                if( obj.size()==0 )
                    throw new MissingValueException("Cannot emit empty output: $targetName")
                channels.put(targetName, target(i, obj.get(0)))
            }

            else {
                if( feedbackChannels!=null )
                    throw new ScriptRuntimeException("Workflow `$name` static output is not allowed when using recursion - Check output: $targetName")
                final value = CH.create(true)
                value.bind(obj)
                channels.put(targetName, value)
            }
        }
        return new ChannelOut(channels)
    }

    protected DataflowWriteChannel target(int index, Object output) {
        if( feedbackChannels==null )
            return (DataflowWriteChannel)output
        // otherwise the output should be forwarded into the feedback channel
        final feedback = feedbackChannels[index]
        CH.getReadChannel(output).into( feedback )
        return feedback
    }

    Object run(Object[] args) {
        binding = new WorkflowBinding(owner)
        ExecutionStack.push(this)
        try {
            return run0(args)
        }
        catch (MissingMethodException e) {
            throw new MissingProcessException(this.binding.scriptMeta, e)
        }
        finally {
            ExecutionStack.pop()
        }
    }

    private Object run0(Object[] args) {
        collectInputs(binding, args)
        // invoke the workflow execution
        final closure = body.closure
        closure.setDelegate(new WorkflowDsl(binding))
        closure.setResolveStrategy(Closure.DELEGATE_FIRST)
        closure.call()
        // collect the workflow outputs
        output = collectOutputs(declaredOutputs)
        // publish the workflow outputs
        if( publisher ) {
            final cl = (Closure)publisher.clone()
            cl.setDelegate(new WorkflowPublishDsl(binding))
            cl.setResolveStrategy(Closure.DELEGATE_FIRST)
            cl.call()
        }
        return output
    }

}

/**
 * Implements the DSL for defining workflow takes and emits
 */
@Slf4j
@CompileStatic
class WorkflowParamsDsl {

    static final private String TAKE_PREFIX = '_take_'
    static final private String EMIT_PREFIX = '_emit_'


    Map<String,Object> takes = new LinkedHashMap<>(10)
    Map<String,Object> emits = new LinkedHashMap<>(10)

    @Override
    def invokeMethod(String name, Object args) {
        if( name.startsWith(TAKE_PREFIX) )
            takes.put(name.substring(TAKE_PREFIX.size()), args)

        else if( name.startsWith(EMIT_PREFIX) )
            emits.put(name.substring(EMIT_PREFIX.size()), args)

        else
            throw new MissingMethodException(name, WorkflowDef, args)
    }
}

/**
 * Implements the DSL for executing the workflow
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Slf4j
@CompileStatic
class WorkflowDsl {

    private Binding binding

    WorkflowDsl(Binding binding) {
        this.binding = binding
    }

    @Override
    Object getProperty(String name) {
        try {
            return binding.getProperty(name)
        }
        catch( MissingPropertyException e ){
            return super.getProperty(name)
        }
    }

    @Override
    Object invokeMethod(String name, Object args) {
        if( name == '_into_topic' ) {
            final args0 = args as Object[]
            if( args0[0] instanceof DataflowWriteChannel )
                _into_topic(args0[0] as DataflowWriteChannel, args0[1] as String)
            else if( args0[0] instanceof ChannelOut )
                _into_topic(args0[0] as ChannelOut, args0[1] as String)
            else
                throw new IllegalArgumentException("Workflow topic source should be a channel")
        }
        else
            binding.invokeMethod(name, args)
    }

    void _into_topic(DataflowWriteChannel source, String name) {
        if( !NF.topicChannelEnabled )
            throw new ScriptRuntimeException("Workflow `topic:` section requires the `nextflow.preview.topic` feature flag")
        new IntoTopicOp(CH.getReadChannel(source), name).apply()
    }

    void _into_topic(ChannelOut out, String name) {
        if( !NF.topicChannelEnabled )
            throw new ScriptRuntimeException("Workflow `topic:` section requires the `nextflow.preview.topic` feature flag")
        if( out.size() != 1 )
            throw new IllegalArgumentException("Cannot send a multi-channel output into a topic")
        _into_topic(out[0], name)
    }

}

/**
 * Implements the DSL for publishing workflow outputs
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class WorkflowPublishDsl {

    private static final List<String> PUBLISH_OPTIONS = List.of(
        'contentType',
        'enabled',
        'ignoreErrors',
        'mode',
        'overwrite',
        'pattern',
        'storageClass',
        'tags'
    )

    private Binding binding

    private Path directory = Paths.get('.').complete()

    private Map defaults = [:]

    private boolean directoryOnce = false

    WorkflowPublishDsl(Binding binding) {
        this.binding = binding
    }

    @Override
    Object getProperty(String name) {
        try {
            return binding.getProperty(name)
        }
        catch( MissingPropertyException e ){
            return super.getProperty(name)
        }
    }

    void directory(Map defaults=[:], String directory) {
        if( directoryOnce )
            throw new ScriptRuntimeException("Output directory cannot be defined more than once in the workflow output definition")
        directoryOnce = true

        this.directory = (directory as Path).complete()
        this.defaults = defaults
    }

    void path(String path, Closure closure) {
        final dsl = new PathDsl(directory.resolve(path), defaults)
        final cl = (Closure)closure.clone()
        cl.setResolveStrategy(Closure.DELEGATE_FIRST)
        cl.setDelegate(dsl)
        cl.call()
    }

    class PathDsl {

        private Path path
        private Map defaults
        private boolean defaultsOnce = false

        PathDsl(Path path, Map defaults) {
            this.path = path
            this.defaults = defaults
        }

        void defaults(Map opts) {
            if( defaultsOnce )
                throw new ScriptRuntimeException("Publish defaults cannot be defined more than once for a given path")
            defaultsOnce = true

            validatePublishOptions(opts)
            defaults.putAll(opts)
        }

        void path(String subpath, Closure closure) {
            final dsl = new PathDsl(path.resolve(subpath), defaults)
            final cl = (Closure)closure.clone()
            cl.setResolveStrategy(Closure.DELEGATE_FIRST)
            cl.setDelegate(dsl)
            cl.call()
        }

        void from(Map opts=[:], DataflowWriteChannel source) {
            validatePublishOptions(opts)
            if( opts.ignoreErrors )
                opts.failOnError = !opts.remove('ignoreErrors')
            new PublishOp(CH.getReadChannel(source), defaults + opts + [path: path]).apply()
        }

        void from(Map opts=[:], ChannelOut out) {
            if( out.size() != 1 )
                throw new IllegalArgumentException("Cannot publish a multi-channel output")
            from(opts, out[0])
        }

        void from(Map opts=[:], String name) {
            if( !NF.topicChannelEnabled ) throw new ScriptRuntimeException("Topic selector in workflow output definition requires the `nextflow.preview.topic` feature flag")
            from(opts, CH.topic(name))
        }

        private void validatePublishOptions(Map opts) {
            for( final name : opts.keySet() )
                if( name !in PUBLISH_OPTIONS )
                    throw new IllegalArgumentException("Unrecognized publish option '${name}' in the workflow output definition")
        }

    }

}
