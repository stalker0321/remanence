import hashlib
import io
import json
import shutil
import sqlite3
import tarfile
import uuid
from pathlib import Path

import pytest

from remanence.music.catalog_build import CatalogBuildError, build_catalog, iter_documents, publish_revision
from remanence.music.catalog_prepare import Snapshot
from remanence.music.ingestion.identity import track_id_for_recording
from remanence.music.ingestion.normalize import normalize_tables
from remanence.music.ingestion.mbdump import TABLE_SCHEMAS, parse_copy_line
from remanence.music.search.full_catalog import FullCatalogSearch
from remanence.music.ports import MusicSearchError, MusicSearchUnavailableError

COMMIT = "b" * 40
A1 = "11111111-1111-4111-8111-111111111111"
A2 = "22222222-2222-4222-8222-222222222222"
R1 = "33333333-3333-4333-8333-333333333333"
R2 = "44444444-4444-4444-8444-444444444444"


def fixture(tmp_path, *, schema="31", extra=None, tables=None):
    tables = tables or {
        "artist": f"1\t{A1}\tSame Name\n2\t{A2}\tSame Name\n",
        "artist_credit_name": "10\t1\t2\tGuest\\tAlias\t\n10\t0\t1\tPrimary Alias\t & \n",
        "recording": f"1\t{R1}\tSong\\nTitle\t10\t\\N\n2\t{R2}\tSong Title live\t10\t12345\n",
        "isrc": "1\t1\tUSABC1234567\n2\t2\tUSABC1234567\n",
    }
    archive = tmp_path / ("dump-" + uuid.uuid4().hex + ".tar.bz2")
    with tarfile.open(archive,"w:bz2") as tar:
        members = ([] if schema is None else [("SCHEMA_SEQUENCE",schema+"\n")]) + [("mbdump/"+name,text) for name,text in tables.items()]
        if extra: members += extra
        for name,text in members:
            data=text.encode(); info=tarfile.TarInfo(name); info.size=len(data); tar.addfile(info,io.BytesIO(data))
    return archive, Snapshot("20261007-002147",archive.stat().st_size,hashlib.sha256(archive.read_bytes()).hexdigest()), tables


def run_build(tmp_path, **kwargs):
    archive,snapshot,tables=fixture(tmp_path,**kwargs)
    out=tmp_path / ("build-"+uuid.uuid4().hex)
    report=build_catalog(archive,snapshot,out,source_commit=COMMIT,max_build_gib=1,reserve_gib=1)
    return out,report,tables


def test_full_scope_and_existing_identity_credit_order_escaping(tmp_path):
    out,report,tables=run_build(tmp_path)
    assert report["state"]=="READY" and report["artist_sampling"] is False
    assert report["indexed"]==2 and report["tables"]["artist"]["rows_inserted"]==2
    docs=list(iter_documents(out/"catalog.sqlite3"))
    assert docs[0]["id"]==str(track_id_for_recording(R1))
    assert docs[0]["artists"]==["Primary Alias","Guest\tAlias"]
    assert docs[0]["durationMs"] is None and docs[0]["title"]=="Song\nTitle"
    parsed={table:[parse_copy_line(line,TABLE_SCHEMAS[table]) for line in text.splitlines()] for table,text in tables.items()}
    old_records,_=normalize_tables(parsed,artist_limit=1000)
    assert [record.recording_mbid for record in old_records]==[doc["recordingMbid"] for doc in docs]
    assert list(old_records[0].artists)==docs[0]["artists"]
    db=sqlite3.connect(out/"catalog.sqlite3")
    assert json.loads(db.execute("SELECT artist_mbids FROM tracks WHERE recording_id=1").fetchone()[0])==[A1,A2]
    assert db.execute("SELECT count(*) FROM raw_isrc WHERE isrc='USABC1234567'").fetchone()[0]==2
    assert not (out/"catalog.sqlite3.partial").exists()
    assert json.loads((out/"progress.json").read_text())["state"]=="READY"
    db.close()


def test_search_port_and_document_contract(tmp_path):
    out,_,_=run_build(tmp_path)
    adapter=FullCatalogSearch(out/"catalog.sqlite3")
    hits,total=adapter.search_with_total("song primary",20)
    assert total==2 and {hit.id for hit in hits}=={track_id_for_recording(R1),track_id_for_recording(R2)}
    assert len(adapter.search("song",1))==1
    assert adapter.search("unfindable",20)==[]
    assert adapter.search('" OR *',20)==[]  # plain text, never FTS operator injection
    with pytest.raises(MusicSearchError): adapter.search("song",21)
    with pytest.raises(MusicSearchUnavailableError): FullCatalogSearch(tmp_path/"missing.db").search("song",20)


@pytest.mark.parametrize("schema",["30","32","",None])
def test_schema_mismatch_never_complete(tmp_path,schema):
    archive,snapshot,_=fixture(tmp_path,schema=schema)
    out=tmp_path/"bad"
    with pytest.raises(CatalogBuildError,match="schema sequence"):
        build_catalog(archive,snapshot,out,source_commit=COMMIT,max_build_gib=1,reserve_gib=1)
    assert json.loads((out/"manifest.json").read_text())["state"]=="FAILED"
    assert not (out/"catalog.sqlite3").exists()


def test_hash_mismatch_no_artifact(tmp_path):
    archive,snapshot,_=fixture(tmp_path)
    wrong=Snapshot(snapshot.revision,snapshot.archive_bytes,"0"*64)
    with pytest.raises(CatalogBuildError,match="hash mismatch"):
        build_catalog(archive,wrong,tmp_path/"out",source_commit=COMMIT,max_build_gib=1,reserve_gib=1)
    assert not (tmp_path/"out").exists()


@pytest.mark.parametrize("extra",[[('mbdump/artist','1\tbad\tbad\n')],[('../escape','hello')]])
def test_duplicate_or_unsafe_tar_never_ready(tmp_path,extra):
    archive,snapshot,_=fixture(tmp_path,extra=extra)
    with pytest.raises(CatalogBuildError):
        build_catalog(archive,snapshot,tmp_path/"bad",source_commit=COMMIT,max_build_gib=1,reserve_gib=1)
    assert json.loads((tmp_path/"bad"/"manifest.json").read_text())["state"]=="FAILED"


def test_domain_invalid_record_accounted_nonpositive_duration_null(tmp_path):
    archive,snapshot,tables=fixture(tmp_path)
    tables["recording"]+=f"3\t{uuid.uuid4()}\t  \t10\t0\n4\t{uuid.uuid4()}\tZero duration\t10\t0\n"
    out,report,_=run_build(tmp_path,tables=tables)
    assert report["tables"]["recording"]["rows_seen"]==4
    assert report["tables"]["recording"]["domain_invalid"]==1
    assert report["indexed"]==3
    assert [d for d in iter_documents(out/"catalog.sqlite3") if d["title"]=="Zero duration"][0]["durationMs"] is None


def test_no_overwrite_and_atomic_publish_previous(tmp_path):
    out,_,_=run_build(tmp_path)
    archive,snapshot,_=fixture(tmp_path)
    with pytest.raises(CatalogBuildError,match="already exists"):
        build_catalog(archive,snapshot,out,source_commit=COMMIT,max_build_gib=1,reserve_gib=1)
    publish_revision(tmp_path,out,expected_current=None)
    other=tmp_path/"other"; shutil.copytree(out,other)
    publish_revision(tmp_path,other,expected_current=out.name)
    assert json.loads((tmp_path/"previous.json").read_text())["revision"]==out.name
    with pytest.raises(CatalogBuildError,match="changed"):
        publish_revision(tmp_path,out,expected_current=out.name)
    publish_revision(tmp_path,out,expected_current=other.name)
    assert json.loads((tmp_path/"current.json").read_text())["revision"]==out.name


def test_more_than_2000_rows_multiple_credit_track_and_fts_batches(tmp_path):
    _,_,tables=fixture(tmp_path)
    tables["artist_credit_name"]="".join(f"{i}\t0\t1\tAlias {i}\t\n" for i in range(1,2502))
    tables["recording"]="".join(f"{i}\t{uuid.UUID(int=i)}\tSong {i}\t{i}\t\\N\n" for i in range(1,2502))
    tables["isrc"]="".join(f"{i}\t{i}\tUSABC1234567\n" for i in range(1,2502))
    out,report,_=run_build(tmp_path,tables=tables)
    assert report["normalization"]["credits"]==2501
    assert report["indexed"]==2501 and report["tables"]["recording"]["rows_seen"]==2501
    assert FullCatalogSearch(out/"catalog.sqlite3").search_with_total("song",20)[1]==2501


def test_missing_required_table_never_ready(tmp_path):
    _,_,tables=fixture(tmp_path); del tables["recording"]
    archive,snapshot,_=fixture(tmp_path,tables=tables)
    with pytest.raises(CatalogBuildError,match="missing required"):
        build_catalog(archive,snapshot,tmp_path/"bad",source_commit=COMMIT,max_build_gib=1,reserve_gib=1)
    assert json.loads((tmp_path/"bad"/"manifest.json").read_text())["state"]=="FAILED"


def test_invalid_member_never_partially_attributes_credit(tmp_path):
    _,_,tables=fixture(tmp_path)
    tables["artist_credit_name"]+="11\t0\t1\tValid name\t\n11\t1\t999999\tMissing artist\t\n"
    tables["recording"]+=f"3\t{uuid.uuid4()}\tBroken credit\t11\t\\N\n"
    out,report,_=run_build(tmp_path,tables=tables)
    assert report["indexed"]==2 and report["normalization"]["unresolved_recordings"]==1
    assert FullCatalogSearch(out/"catalog.sqlite3").search("broken",20)==[]


def test_byte_capped_batches_also_flush(monkeypatch,tmp_path):
    import remanence.music.catalog_build as module
    monkeypatch.setattr(module,"BATCH_BYTES",32)
    out,report,_=run_build(tmp_path)
    assert report["indexed"]==2
    assert len(list(iter_documents(out/"catalog.sqlite3")))==2


def test_disk_budget_abort_is_explicit(tmp_path):
    from remanence.music.catalog_build import DiskBudget
    (tmp_path/"large").write_bytes(b"x"*128)
    with pytest.raises(CatalogBuildError,match="budget"):
        DiskBudget(tmp_path,64,1).check(force=True)


def test_publish_refuses_manifest_only_false_ready(tmp_path):
    out,_,_=run_build(tmp_path)
    connection=sqlite3.connect(out/"catalog.sqlite3")
    connection.execute("UPDATE build_metadata SET value='FAILED' WHERE key='state'")
    connection.commit(); connection.close()
    with pytest.raises(CatalogBuildError,match="mismatch"):
        publish_revision(tmp_path,out,expected_current=None)
    assert not (tmp_path/"current.json").exists()
