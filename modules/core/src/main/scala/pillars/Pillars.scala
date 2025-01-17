package pillars

import cats.effect.*
import cats.effect.std.Console
import cats.syntax.all.*
import fs2.io.net.Network
import io.circe.Decoder
import java.nio.file.Path
import java.util.ServiceLoader
import org.typelevel.otel4s.trace.Tracer
import pillars.Config.PillarsConfig
import pillars.Config.Reader
import pillars.probes.ProbeManager
import pillars.probes.ProbesController
import scala.jdk.CollectionConverters.IterableHasAsScala
import scribe.*

/**
 * The Pillars trait defines the main components of the application.
 */
trait Pillars[F[_]]:
    /**
     * Component for observability. It allows you to create spans and metrics.
     */
    def observability: Observability[F]

    /**
     * The configuration for the application.
     */
    def config: PillarsConfig

    /**
     * The API server for the application.
     *
     * It has to be manually started by calling the `start` method in the application.
     */
    def apiServer: ApiServer[F]

    /**
     * The logger for the application.
     */
    def logger: Scribe[F]

    /**
     * Reads a configuration from the configuration.
     *
     * @return the configuration.
     */
    def readConfig[T](using Decoder[T]): F[T]

    /**
     * Gets a module from the application.
     *
     * @return the module.
     */
    def module[T](key: Module.Key): T
end Pillars

/**
 * The Pillars object provides methods to initialize the application.
 */
object Pillars:
    /**
     * Creates a new instance of Pillars.
     *
     * Modules are loaded from the classpath using the ServiceLoader mechanism, and are loaded in topological order
     *
     * @param path The path to the configuration file.
     * @return a resource that will create a new instance of Pillars.
     */
    def apply[F[_]: LiftIO: Async: Console: Network](path: Path): Resource[F, Pillars[F]] =
        val configReader = Reader[F](path)
        for
            _config        <- Resource.eval(configReader.read[PillarsConfig])
            obs            <- Resource.eval(Observability.init[F](_config.observability))
            given Tracer[F] = obs.tracer
            _              <- Resource.eval(Logging.init(_config.log))
            _logger         = ScribeImpl[F](Sync[F])
            context         = Loader.Context(obs, configReader, _logger)
            _              <- Resource.eval(_logger.info("Loading modules..."))
            _modules       <- loadModules(context)
            _              <- Resource.eval(_logger.debug(s"Loaded ${_modules.size} modules"))
            probes         <- ProbeManager.build[F](_modules)
            _              <- Spawn[F].background(probes.start())
            _              <- Spawn[F].background:
                                  AdminServer[F](_config.admin, obs, _modules.adminControllers :+ ProbesController(probes)).start()
        yield new Pillars[F]:
            override def observability: Observability[F]       = obs
            override def config: PillarsConfig                 = _config
            override def apiServer: ApiServer[F]               =
                ApiServer.init(config.api, observability, logger)
            override def logger: Scribe[F]                     = _logger
            override def readConfig[T](using Decoder[T]): F[T] = configReader.read[T]
            override def module[T](key: Module.Key): T         = _modules.get(key)
        end for
    end apply

    /**
     * Loads the modules for the application.
     *
     * @param context The context for loading the modules.
     * @return a resource that will instantiate the modules.
     */
    private def loadModules[F[_]: Async: Network: Tracer: Console](context: Loader.Context[F])
        : Resource[F, Modules[F]] =
        val loaders = ServiceLoader.load(classOf[Loader])
            .asScala
            .toList
        scribe.info(s"Found ${loaders.size} module loaders: ${loaders.map(_.name).mkString(", ")}")
        loaders.topologicalSort(_.dependsOn) match
        case Left(value)  => throw IllegalStateException("Circular dependency detected in modules")
        case Right(value) =>
            value.foldLeftM(Modules.empty[F]): (acc, loader) =>
                loader.load(context, acc).map(acc.add)
        end match
    end loadModules

end Pillars
