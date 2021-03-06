package leakcanary.internal.activity.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.AsyncTask
import leakcanary.CanaryLog
import leakcanary.HeapAnalysis
import leakcanary.HeapAnalysisFailure
import leakcanary.HeapAnalysisSuccess
import leakcanary.HeapDump
import leakcanary.Serializables
import leakcanary.internal.LeakCanaryUtils
import leakcanary.internal.activity.db.LeakingInstanceTable.HeapAnalysisGroupProjection
import leakcanary.leakingInstances
import leakcanary.toByteArray
import org.intellij.lang.annotations.Language

internal object HeapAnalysisTable {

  @Language("RoomSql")
  const val create = """CREATE TABLE heap_analysis
        (
        id INTEGER PRIMARY KEY,
        created_at_time_millis INTEGER,
        retained_instance_count INTEGER DEFAULT 0,
        exception_summary TEXT DEFAULT NULL,
        object BLOB
        )"""

  fun insert(
    db: SQLiteDatabase,
    heapAnalysis: HeapAnalysis
  ): Long {
    val values = ContentValues()
    values.put("created_at_time_millis", heapAnalysis.createdAtTimeMillis)
    values.put("object", heapAnalysis.toByteArray())
    when (heapAnalysis) {
      is HeapAnalysisSuccess -> {
        values.put("retained_instance_count", heapAnalysis.retainedInstances.size)
      }
      is HeapAnalysisFailure -> {
        val cause = heapAnalysis.exception.cause!!
        val exceptionSummary = "${cause.javaClass.simpleName} ${cause.message}"
        values.put("exception_summary", exceptionSummary)
      }
    }

    return db.inTransaction {
      val heapAnalysisId = db.insertOrThrow("heap_analysis", null, values)
      heapAnalysis.leakingInstances()
          .forEach { leakingInstance ->
            LeakingInstanceTable.insert(
                db, heapAnalysisId, leakingInstance
            )
          }
      heapAnalysisId
    }
  }

  fun <T : HeapAnalysis> retrieve(
    db: SQLiteDatabase,
    id: Long
  ): Pair<T, Map<String, HeapAnalysisGroupProjection>>? {
    db.inTransaction {
      val heapAnalysis = db.rawQuery(
          """
              SELECT
              object
              FROM heap_analysis
              WHERE id=$id
              """, null
      )
          .use { cursor ->
            if (cursor.moveToNext()) {
              val analysis = Serializables.fromByteArray<T>(cursor.getBlob(0))
              if (analysis == null) {
                delete(db, id, null)
              }
              analysis
            } else
              null
          } ?: return null

      val hashes =
        LeakingInstanceTable.retrieveAllByHeapAnalysisId(db, id)

      return heapAnalysis to hashes
    }
  }

  fun retrieveAll(db: SQLiteDatabase): List<Projection> {
    return db.rawQuery(
        """
          SELECT
          id
          , created_at_time_millis
          , retained_instance_count
          , exception_summary
          FROM heap_analysis
          ORDER BY created_at_time_millis DESC
          """, null
    )
        .use { cursor ->
          val all = mutableListOf<Projection>()
          while (cursor.moveToNext()) {
            val summary = Projection(
                id = cursor.getLong(0),
                createdAtTimeMillis = cursor.getLong(1),
                retainedInstanceCount = cursor.getInt(2),
                exceptionSummary = cursor.getString(3)
            )
            all.add(summary)
          }
          all
        }
  }

  fun delete(
    db: SQLiteDatabase,
    id: Long,
    heapDump: HeapDump?
  ) {
    if (heapDump != null) {
      AsyncTask.SERIAL_EXECUTOR.execute {
        val heapDumpDeleted = heapDump.heapDumpFile.delete()
        if (!heapDumpDeleted) {
          CanaryLog.d("Could not delete heap dump file %s", heapDump.heapDumpFile.path)
        }
      }
    }

    db.inTransaction {
      db.delete("heap_analysis", "id=$id", null)
      LeakingInstanceTable.deleteByHeapAnalysisId(db, id)
    }
  }

  fun deleteAll(
    db: SQLiteDatabase,
    context: Context
  ) {
    val leakDirectoryProvider = LeakCanaryUtils.getLeakDirectoryProvider(context)
    AsyncTask.SERIAL_EXECUTOR.execute { leakDirectoryProvider.clearLeakDirectory() }
    db.inTransaction {
      db.delete("heap_analysis", null, null)
      LeakingInstanceTable.deleteAll(db)
    }
  }

  class Projection(
    val id: Long,
    val createdAtTimeMillis: Long,
    val retainedInstanceCount: Int,
    val exceptionSummary: String?
  )

}