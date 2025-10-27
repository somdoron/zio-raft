package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.raft.{Command, HMap, Index}
import zio.raft.protocol.{SessionId, RequestId}
import java.time.Instant

object SessionLifecycleSpec extends ZIOSpecDefault:

  sealed trait TestCommand extends Command
  object TestCommand:
    case object Noop extends TestCommand:
      type Response = Unit

  type TestResponse = Unit
  type TestSchema = EmptyTuple
  type CombinedSchema = Tuple.Concat[SessionSchema[TestResponse, String], TestSchema]

  // Minimal codecs
  import scodec.codecs.*
  given scodec.Codec[Unit] = provide(())
  import zio.raft.sessionstatemachine.Codecs.{sessionMetadataCodec, requestIdCodec, pendingServerRequestCodec}
  given scodec.Codec[PendingServerRequest[?]] =
    summon[scodec.Codec[PendingServerRequest[String]]].asInstanceOf[scodec.Codec[PendingServerRequest[?]]]

  class TestStateMachine extends SessionStateMachine[TestCommand, TestResponse, String, TestSchema]
      with ScodecSerialization[TestResponse, String, TestSchema]:

    val codecs = summon[HMap.TypeclassMap[CombinedSchema, scodec.Codec]]

    protected def applyCommand(
      createdAt: Instant,
      sessionId: SessionId,
      cmd: TestCommand
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], cmd.Response & TestResponse] =
      StateWriter.succeed(().asInstanceOf[cmd.Response & TestResponse])

    protected def handleSessionCreated(
      createdAt: Instant,
      sid: SessionId,
      caps: Map[String, String]
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Unit] =
      // Emit a server request to another session (admin) upon session creation
      StateWriter.log(ServerRequestForSession[String](SessionId("admin"), s"created:${SessionId.unwrap(sid)}"))
        .as(())

    protected def handleSessionExpired(
      createdAt: Instant,
      sid: SessionId,
      capabilities: Map[String, String]
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Unit] =
      // Emit a server request to another session (admin) upon session expiration
      StateWriter.log(ServerRequestForSession[String](SessionId("admin"), s"expired:${SessionId.unwrap(sid)}"))
        .as(())

    override def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean =
      false

  def spec = suite("Session lifecycle with cross-session server requests (T017)")(
    test("CreateSession emits admin request and SessionExpired cleans all session data") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[sm.Schema]
      val now = Instant.now()
      val sid = SessionId("s1")

      // Create session
      val create =
        SessionCommand.CreateSession[String](now, sid, Map("k" -> "v"))
          .asInstanceOf[SessionCommand[TestCommand, String]]
      val (state1, _) = sm.apply(create).run(state0)

      // Verify admin request exists
      val h1 = state1.asInstanceOf[HMap[CombinedSchema]]
      val hasAdminCreated = h1.iterator["serverRequests"].exists { case ((sess, _), pending) =>
        sess == SessionId("admin") && pending.payload == s"created:${SessionId.unwrap(sid)}"
      }
      assertTrue(hasAdminCreated)

      // Expire session
      val expire =
        SessionCommand.SessionExpired[String](now, sid)
          .asInstanceOf[SessionCommand[TestCommand, String]]
      val (state2, _) = sm.apply(expire).run(state1)

      // Verify session data cleaned
      val h2 = state2.asInstanceOf[HMap[CombinedSchema]]
      val noMetadata = h2.get["metadata"](sid).isEmpty
      val noCache = !h2.iterator["cache"].exists { case ((sess, _), _) => sess == sid }
      val noServerRequests = !h2.iterator["serverRequests"].exists { case ((sess, _), _) => sess == sid }
      val noLastId = h2.get["lastServerRequestId"](sid).isEmpty

      assertTrue(noMetadata && noCache && noServerRequests && noLastId)
    }
  )
end SessionLifecycleSpec
