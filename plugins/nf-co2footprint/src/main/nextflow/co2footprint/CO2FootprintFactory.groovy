/*
 * Copyright 2021, Seqera Labs
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

package nextflow.co2footprint

import groovy.text.GStringTemplateEngine
import groovy.transform.PackageScope
import groovy.transform.PackageScopeTarget
import groovyx.gpars.agent.Agent
import nextflow.processor.TaskHandler
import nextflow.processor.TaskProcessor
import nextflow.script.WorkflowMetadata
import nextflow.trace.TraceHelper
import nextflow.trace.TraceRecord
import nextflow.co2footprint.HelperFunctions

import java.nio.file.Files
import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.trace.TraceObserver
import nextflow.trace.TraceObserverFactory
import nextflow.processor.TaskId

import java.util.concurrent.ConcurrentHashMap

/**
 * Implements the CO2Footprint observer factory
 *
 * @author Júlia Mir Pedrol <mirp.julia@gmail.com>, Sabrina Krakau <sabrinakrakau@gmail.com>
 */
@Slf4j
@CompileStatic
@PackageScope(PackageScopeTarget.FIELDS)
class CO2FootprintFactory implements TraceObserverFactory {

    private CO2FootprintConfig config
    private Session session
    final private Map<TaskId,CO2Record> co2eRecords = new ConcurrentHashMap<>()
    // TODO make sure for key value can be set only once?

    private Map<String, Double> cpuData = ['default': (Double) 12.0]
    Double total_energy = 0
    Double total_co2 = 0


    // Load file containing TDP values for different CPU models
    protected void loadCpuTdpData(Map<String, Double> data) {
        def dataReader = new InputStreamReader(this.class.getResourceAsStream('/cpu_tdp_values.csv'))

        String line
        while ( line = dataReader.readLine() ) {
            def h = line.split(",")
            if (h[0] != 'model_name') data[h[0]] = h[3].toDouble()
        }
        dataReader.close()
        log.debug "$data"
    }

    @Override
    Collection<TraceObserver> create(Session session) {
        this.session = session
        this.config = new CO2FootprintConfig(session.config.navigate('co2footprint') as Map)
        loadCpuTdpData(this.cpuData)

        final result = new ArrayList(2)
        // Generate CO2 footprint text output files
        def co2eFile = (this.config.getFile() as Path).complete()
        def co2eSummaryFile = (this.config.getSummaryFile() as Path).complete()

        result.add( new CO2FootprintTextFileObserver(co2eFile, co2eSummaryFile) )

        // Generate CO2 footprint report with box-plot
        def co2eReport = (this.config.getReportFile() as Path).complete()
        result.add( new CO2FootprintReportObserver(co2eReport) )

        return result
    }

    
    Double getCpuCoreTdp(TraceRecord trace) {
        def cpu_model = trace.get('cpu_model').toString()   // TODO toString() in TraceRecord get()?

        // Look up CPU model specific TDP value
        def c = 0
        while ( true ) {
            if ( cpuData.containsKey(cpu_model) ){
                return cpuData[cpu_model]
            } else if ( c < 2) {
                // Trim suffixes, e.g. " Processor" or " 16-Core Processor", and try again
                // TODO what are valid cases here?
                def i = cpu_model.lastIndexOf(' ')
                if ( i == -1 )
                    break
                else
                    cpu_model = cpu_model.substring(0, i)
            } else {
                break
            }
            c++
        }
        return cpuData['default']
    }


    // Core function to compute CO2 emissions for each task
    List<Double> computeTaskCO2footprint(TraceRecord trace) {
        // C = t * (nc * Pc * uc + nm * Pm) * PUE * CI * 0.001
        // as in https://doi.org/10.1002/advs.202100707
        // PSF: pragmatic scaling factor -> not used here since we aim at the CO2e of one pipeline run
        // Factor 0.001 needed to convert Pc and Pm from W to kW

        // t: runtime in hours
        Double realtime = trace.get('realtime') as Double
        Double t = realtime/3600000 as Double

        /**
         * Factors of core power usage
         */
        // nc: number of cores
        Double nc = trace.get('cpus') as Integer

        // Pc: power draw of a computing core  [W]
        Double pc = getCpuCoreTdp(trace)

        // uc: core usage factor (between 0 and 1)
        // TODO if requested more than used, this is not taken into account, right?
        Double cpu_usage = trace.get('%cpu') as Double
        if ( cpu_usage == null ) {
            log.warn "cpu_usage is null"
            // TODO why is value null, because task was finished so fast that it was not captured? Or are there other reasons?
            // Assuming requested cpus were used with 100%
            cpu_usage = nc * 100
        }
        // TODO how to handle double, Double datatypes for ceiling?
        Double cpus_ceil = Math.ceil( cpu_usage / 100.0 as double )
        Double uc = cpu_usage / (100.0 * cpus_ceil) as Double

        /**
         * Factors of memory power usage
         */
        // nm: size of memory available [GB] -> requested memory
        Long memory = trace.get('memory') as Long
        if ( memory == null ) {
            // TODO if 'memory' not set, returns null, hande somehow?
            log.error "TraceRecord field 'memory' is not set!"
            System.exit(1)
        }
        Double nm = memory/1000000000 as Double
        // TODO handle if more memory/cpus used than requested?

        // Pm: power draw of memory [W per GB]
        Double pm  = config.getPowerdrawMem()

        /**
         * Remaining factors
         */
        // PUE: efficiency coefficient of the data centre
        Double pue = config.getPUE()
        // CI: carbon intensity [gCO2e kWh−1]
        def ci  = config.getCI()

        /**
         * Calculate energy consumption [kWh]
         */
        Double e = (t * (nc * pc * uc + nm * pm) * pue * 0.001) as Double

        /*
         * Resulting CO2 emission [gCO2e]
         */
        Double c = (e * ci)

        // Return values in mWh and mg
        e = e * 1000000
        c = c * 1000

        return [e, c, realtime, nc, pc, uc, memory]
    }


    // Compute CO2 footprint equivalences
    List<Double> computeCO2footprintEquivalences() {
        /*
         * The following values were taken from the Green Algorithms publication (https://doi.org/10.1002/advs.202100707):
         * The estimated emission of the average passenger car is 175 gCO2e/Km in Europe and 251 gCO2/Km in the US
         * The estimated emission of flying on a jet aircraft in economy class is between 139 and 244 gCO2e/Km
         * The estimated sequestered CO2 of a mature tree is ~1 Kg per month (917 g)
         * A reference flight Paris to London spends 50000 gCO2
         */
        def gCO2 = total_co2 / 1000 as Double
        String country = config.getCountry()
        Double car = gCO2 / 175 as Double
        if (country && country == 'United States of America') {
            car = gCO2 / 251 as Double
        }
        Double tree = gCO2 / 917 as Double
        car = car.round(2)
        tree = tree.round(2)
        Double plane_percent
        Double plane_flights
        if (gCO2 <= 50000) {
            plane_percent = gCO2 * 100 / 50000 as Double
            plane_percent = plane_percent.round(2)
        } else {
            plane_flights = gCO2 / 50000 as Double
            plane_flights = plane_flights.round(2)
        }

        return [car, tree, plane_percent, plane_flights]
    }


    /**
     * Class to generate text output
     */
    class CO2FootprintTextFileObserver implements TraceObserver {

        // TODO which files should we generate here?
        public static final String DEF_FILE_NAME = "co2footprint-${TraceHelper.launchTimestampFmt()}.txt"
        public static final String DEF_SUMMARY_FILE_NAME = "co2footprint-${TraceHelper.launchTimestampFmt()}.summary.txt"

        /**
         * Overwrite existing trace file (required in some cases, as rolling filename has been deprecated)
         */
        boolean overwrite = true

        /**
         * The path where the file is created. It is set by the object constructor
         */
        private Path co2ePath
        private Path co2eSummaryPath

        /**
         * The actual file object
         */
        private PrintWriter co2eFile
        private PrintWriter co2eSummaryFile


        /**
         * Holds the the start time for tasks started/submitted but not yet completed
         */
        @PackageScope
        Map<TaskId, TraceRecord> current = new ConcurrentHashMap<>()


        private Agent<PrintWriter> writer
        private Agent<PrintWriter> summaryWriter


        /**
         * Create the trace observer
         *
         * @param co2eFile A path to the file where save the CO2 emission data
         */
        CO2FootprintTextFileObserver(Path co2eFile, Path co2eSummaryFile) {
            this.co2ePath = co2eFile
            this.co2eSummaryPath = co2eSummaryFile
        }

        /** ONLY FOR TESTING PURPOSE */
        protected CO2FootprintTextFileObserver() {}


        /**
         * Create the trace file, in file already existing with the same name it is
         * "rolled" to a new file
         */
        @Override
        void onFlowCreate(Session session) {
            log.debug "Workflow started -- co2e file: ${co2ePath.toUriString()}"

            // make sure parent path exists
            def parent = co2ePath.getParent()
            if (parent)
                Files.createDirectories(parent)

            def summaryParent = co2eSummaryPath.getParent()
            if (summaryParent)
                Files.createDirectories(summaryParent)

            // create a new trace file
            co2eFile = new PrintWriter(TraceHelper.newFileWriter(co2ePath, overwrite, 'co2footprint'))
            co2eSummaryFile = new PrintWriter(TraceHelper.newFileWriter(co2eSummaryPath, overwrite, 'co2footprintsummary'))

            // launch the agent
            writer = new Agent<PrintWriter>(co2eFile)
            summaryWriter = new Agent<PrintWriter>(co2eSummaryFile)

            writer.send { co2eFile.println(
                    "task_id\t"
                    + "energy_consumption\t"
                    + "CO2e\t"
                    + "time\t"
                    + "cpus\t"
                    + "powerdraw_cpu\t"
                    + "cpu_usage\t"
                    + "requested_memory"
                ); co2eFile.flush()
            }
        }

        /**
         * Save the pending processes and close the trace file
         */
        @Override
        void onFlowComplete() {
            log.debug "Workflow completed -- saving trace file"

            // wait for termination and flush the agent content
            writer.await()

            //writer.send { co2eFile.println("Test CO2 emission is:"); co2eFile.flush() }
            //writer.send { PrintWriter it -> it.println("Test CO2 emission is:"); it.flush() }
            co2eSummaryFile.println("The total CO2 emission is: ${HelperFunctions.convertToReadableUnits(total_co2,3)}g")
            co2eSummaryFile.println("The total energy consumption is: ${HelperFunctions.convertToReadableUnits(total_energy,3)}Wh")
            co2eSummaryFile.flush()
            co2eSummaryFile.close()

            // write the remaining records
            current.values().each { taskId, record -> co2eFile.println("${taskId}\t-") }
            co2eFile.flush()
            co2eFile.close()
        }

        @Override
        void onProcessCreate(TaskProcessor process) {

        }

        /**
         * This method is invoked before a process run is going to be submitted
         * @param handler
         */
        @Override
        void onProcessSubmit(TaskHandler handler, TraceRecord trace) {
            current[trace.taskId] = trace
        }

        /**
         * This method is invoked when a process run is going to start
         * @param handler
         */
        @Override
        void onProcessStart(TaskHandler handler, TraceRecord trace) {
            current[trace.taskId] = trace
        }

        /**
         * This method is invoked when a process run completes
         * @param handler
         */
        // TODO write footprint for each process?
        @Override
        void onProcessComplete(TaskHandler handler, TraceRecord trace) {
            final taskId = handler.task.id
            if (!trace) {
                log.debug "[WARN] Unable to find record for task run with id: ${taskId}"
                return
            }

            // remove the record from the current records
            current.remove(taskId)

            // compute the CO2 footprint
            def computation_results = computeTaskCO2footprint(trace)
            def eConsumption = computation_results[0]
            def co2 = computation_results[1]
            def time = computation_results[2]
            def cpus = computation_results[3] as Integer
            def powerdrawCPU = computation_results[4]
            def cpu_usage = computation_results[5]
            def memory = computation_results[6]

            co2eRecords[taskId] = new CO2Record(
                    (Double) eConsumption,
                    (Double) co2,
                    (Double) time,
                    cpus,
                    (Double) powerdrawCPU,
                    (Double) cpu_usage,
                    (Long) memory,
                    trace.get('name').toString()
            )
            total_energy += eConsumption
            total_co2 += co2

            // save to the file
            writer.send {
                PrintWriter it -> it.println(
                        "${taskId}\t${HelperFunctions.convertToReadableUnits(eConsumption,3)}Wh\t"
                        + "${HelperFunctions.convertToReadableUnits(co2,3)}g\t"
                        + "${HelperFunctions.convertMillisecondsToReadableUnits(time)}\t"
                        + "${cpus}\t"
                        + "${powerdrawCPU}\t"
                        + "${cpu_usage}\t"
                        + "${HelperFunctions.convertBytesToReadableUnits(memory)}"
                );
                it.flush()
            }
        }


        @Override
        void onProcessCached(TaskHandler handler, TraceRecord trace) {
            def taskId = handler.task.id    // TODO "final" or "def"?
            // event was triggered by a stored task, ignore it
            if (trace == null) {
                return
            }

            // compute the CO2 footprint
            def computation_results = computeTaskCO2footprint(trace)
            def eConsumption = computation_results[0]
            def co2 = computation_results[1]
            def time = computation_results[2]
            def cpus = computation_results[3] as Integer
            def powerdrawCPU = computation_results[4]
            def cpu_usage = computation_results[5]
            def memory = computation_results[6]

            co2eRecords[taskId] = new CO2Record(
                    (Double) eConsumption,
                    (Double) co2,
                    (Double) time,
                    cpus,
                    (Double) powerdrawCPU,
                    (Double) cpu_usage,
                    (Long) memory,
                    trace.get('name').toString()
            )
            total_energy += eConsumption
            total_co2 += co2

            // save to the file
            writer.send {
                PrintWriter it -> it.println(
                        "${taskId}\t${HelperFunctions.convertToReadableUnits(eConsumption,3)}Wh\t"
                        + "${HelperFunctions.convertToReadableUnits(co2,3)}g\t"
                        + "${HelperFunctions.convertMillisecondsToReadableUnits(time)}\t"
                        + "${cpus}\t"
                        + "${powerdrawCPU}\t"
                        + "${cpu_usage}\t"
                        + "${HelperFunctions.convertBytesToReadableUnits(memory)}"
                );
                it.flush()
            }
        }
    }


    /**
     * Class to generate HTML report with box-plots
     */
    class CO2FootprintReportObserver implements TraceObserver {

        static final public String DEF_REPORT_FILE_NAME = "co2footprint-report-${TraceHelper.launchTimestampFmt()}.html"

        static final public int DEF_MAX_TASKS = 10_000

        /**
         * Holds the the start time for tasks started/submitted but not yet completed
         */
        final private Map<TaskId, TraceRecord> records = new LinkedHashMap<>()

        /**
         * Holds workflow session
         */
        private Session session

        /**
         * The path the HTML report file created
         */
        private Path reportFile

        /**
         * Max number of tasks allowed in the report, when they exceed this
         * number the tasks table is omitted
         */
        private int maxTasks = DEF_MAX_TASKS

        /**
         * Compute resources usage stats
         */
        private CO2FootprintResourcesAggregator aggregator

        /**
         * Overwrite existing trace file (required in some cases, as rolling filename has been deprecated)
         */
        boolean overwrite

        /**
         * Creates a report observer
         *
         * @param file The file path where to store the resulting HTML report document
         */
        CO2FootprintReportObserver(Path file ) {
            this.reportFile = file
        }

        /**
         * Enables the collection of the task executions metrics in order to be reported in the HTML report
         *
         * @return {@code true}
         */
        @Override
        boolean enableMetrics() {
            return true
        }

        /**
         * @return The {@link nextflow.script.WorkflowMetadata} object associated to this execution
         */
        protected WorkflowMetadata getWorkflowMetadata() {
            session.getWorkflowMetadata()
        }

        /**
         * @return The map of collected {@link TraceRecord}s
         */
        protected Map<TaskId,TraceRecord> getRecords() {
            records
        }

        /**
         * @return The map of collected {@link CO2Record}s
         */
        protected Map<TaskId,CO2Record> getCO2Records() {
            co2eRecords
        }

        /**
         * Set the number max allowed tasks. If this number is exceed the the tasks
         * json in not included in the final report
         *
         * @param value The number of max task record allowed to be included in the HTML report
         * @return The {@link CO2FootprintReportObserver} itself
         */
        CO2FootprintReportObserver setMaxTasks(int value ) {
            this.maxTasks = value
            return this
        }

        /**
         * Create the trace file, in file already existing with the same name it is
         * "rolled" to a new file
         */
        @Override
        void onFlowCreate(Session session) {
            this.session = session
            this.aggregator = new CO2FootprintResourcesAggregator(session)
        }

        /**
         * Save the pending processes and close the trace file
         */
        @Override
        void onFlowComplete() {
            log.debug "Workflow completed -- rendering execution report"
            try {
                renderHtml()
            }
            catch (Exception e) {
                log.warn "Failed to render execution report -- see the log file for details", e
            }
        }

        /**
         * This method is invoked when a process is created
         *
         * @param process A {@link TaskProcessor} object representing the process created
         */
        @Override
        void onProcessCreate(TaskProcessor process) { }


        /**
         * This method is invoked before a process run is going to be submitted
         *
         * @param handler A {@link TaskHandler} object representing the task submitted
         */
        @Override
        void onProcessSubmit(TaskHandler handler, TraceRecord trace) {
            log.trace "Trace report - submit process > $handler"
            synchronized (records) {
                records[ trace.taskId ] = trace
            }
        }

        /**
         * This method is invoked when a process run is going to start
         *
         * @param handler  A {@link TaskHandler} object representing the task started
         */
        @Override
        void onProcessStart(TaskHandler handler, TraceRecord trace) {
            log.trace "Trace report - start process > $handler"
            synchronized (records) {
                records[ trace.taskId ] = trace
            }
        }

        /**
         * This method is invoked when a process run completes
         *
         * @param handler A {@link TaskHandler} object representing the task completed
         */
        @Override
        void onProcessComplete(TaskHandler handler, TraceRecord trace) {
            log.trace "Trace report - complete process > $handler"
            if( !trace ) {
                log.debug "WARN: Unable to find trace record for task id=${handler.task?.id}"
                return
            }

            synchronized (records) {
                records[ trace.taskId ] = trace
                aggregate(co2eRecords[ trace.taskId ], trace.getSimpleName())
            }
        }

        /**
         * This method is invoked when a process run cache is hit
         *
         * @param handler A {@link TaskHandler} object representing the task cached
         */
        @Override
        void onProcessCached(TaskHandler handler, TraceRecord trace) {
            log.trace "Trace report - cached process > $handler"

            // event was triggered by a stored task, ignore it
            if( trace == null ) {
                return
            }

            // remove the record from the current records
            synchronized (records) {
                records[ trace.taskId ] = trace
                aggregate(co2eRecords[ trace.taskId ], trace.getSimpleName())
            }
        }

        /**
         * Aggregates task record for each process in order to render the
         * final execution stats
         *
         * @param record A {@link TraceRecord} object representing a task executed
         */
        protected void aggregate(CO2Record co2record, String process) {
            aggregator.aggregate(co2record, process)
        }

        /**
         * @return The tasks json payload
         */
        protected String renderTasksJson() {
            final r = getRecords()
            final co2r = getCO2Records()
            co2r.size()<=maxTasks ? renderJsonData(r.values(), co2r) : 'null'
        }

        protected String renderSummaryJson() {
            final result = aggregator.renderSummaryJson()
            log.debug "Execution report summary data:\n  ${result}"
            return result
        }

        protected String renderPayloadJson() {
            "{ \"trace\":${renderTasksJson()}, \"summary\":${renderSummaryJson()} }"
        }

        /**
         * Render the total co2 footprint values for html report
         *
         * @param data A collection of {@link TraceRecord}s representing the tasks executed
         * @param dataCO2 A collection of {@link CO2Record}s representing the tasks executed
         * @return The rendered json
         */
        protected Map renderCO2TotalsJson() {
            List equivalences = computeCO2footprintEquivalences()
            [ co2:HelperFunctions.convertToReadableUnits(total_co2,3), 
              energy:HelperFunctions.convertToReadableUnits(total_energy,3),
              car: equivalences[0],
              tree: equivalences[1],
              plane_percent: equivalences[2],
              plane_flights: equivalences[3]
            ]
        }

        /**
         * Render the report HTML document
         */
        protected void renderHtml() {

            // render HTML report template
            final tpl_fields = [
                    workflow : getWorkflowMetadata(),
                    payload : renderPayloadJson(),
                    co2_totals: renderCO2TotalsJson(),
                    assets_css : [
                            readTemplate('nextflow/trace/assets/bootstrap.min.css'),
                            readTemplate('nextflow/trace/assets/datatables.min.css')
                    ],
                    assets_js : [
                            readTemplate('nextflow/trace/assets/jquery-3.2.1.min.js'),
                            readTemplate('nextflow/trace/assets/popper.min.js'),
                            readTemplate('nextflow/trace/assets/bootstrap.min.js'),
                            readTemplate('nextflow/trace/assets/datatables.min.js'),
                            readTemplate('nextflow/trace/assets/moment.min.js'),
                            readTemplate('nextflow/trace/assets/plotly.min.js'),
                            readTemplate('assets/CO2FootprintReportTemplate.js')
                    ]
            ]
            //log.info "${tpl_fields['payload']}"
            final tpl = readTemplate('CO2FootprintReportTemplate.html')
            def engine = new GStringTemplateEngine()
            def html_template = engine.createTemplate(tpl)
            def html_output = html_template.make(tpl_fields).toString()

            // make sure the parent path exists
            def parent = reportFile.getParent()
            if( parent )
                Files.createDirectories(parent)

            def writer = TraceHelper.newFileWriter(reportFile, overwrite, 'Report')
            writer.withWriter { w -> w << html_output }
            writer.close()
        }

        /**
         * Render the executed tasks json payload
         *
         * @param data A collection of {@link TraceRecord}s representing the tasks executed
         * @param dataCO2 A collection of {@link CO2Record}s representing the tasks executed
         * @return The rendered json payload
         */
        protected String renderJsonData(Collection<TraceRecord> data, Map<TaskId,CO2Record> dataCO2) {
            def List<String> formats = null
            def List<String> fields = null
            def List<String> co2Formats = null
            def List<String> co2Fields = null
            def result = new StringBuilder()
            result << '[\n'
            for (int i = 0; i < data.size(); i++) {
                if( i ) result << ','
                if( !formats ) formats = TraceRecord.FIELDS.values().collect { it!='str' ? 'num' : 'str' }
                if( !fields ) fields = TraceRecord.FIELDS.keySet() as List
                data[i].renderJson(result,fields,formats)
                if( !co2Formats ) co2Formats = CO2Record.FIELDS.values().collect { it!='str' ? 'num' : 'str' }
                if( !co2Fields ) co2Fields = CO2Record.FIELDS.keySet() as List
                dataCO2[data[i].getTaskId()].renderJson(result,co2Fields,co2Formats)
            }
            result << ']'
            return result.toString()
        }

        /**
         * Read the document HTML template from the application classpath
         *
         * @param path A resource path location
         * @return The loaded template as a string
         */
        private String readTemplate( String path ) {
            StringWriter writer = new StringWriter();
            def res =  this.class.getClassLoader().getResourceAsStream( path )
            int ch
            while( (ch=res.read()) != -1 ) {
                writer.append(ch as char);
            }
            writer.toString();
        }

    }

}
