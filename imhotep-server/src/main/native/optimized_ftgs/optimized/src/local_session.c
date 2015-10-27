#include <stddef.h>
#include <string.h>
#include <errno.h>
#include "imhotep_native.h"
#include "circ_buf.h"
#include "remote_output.h"

#include "high_perf_timer.h"

#define CIRC_BUFFER_SIZE 64
#define PREFETCH_BUFFER_SIZE 64

int run_tgs_pass(struct worker_desc *worker,
                 struct session_desc *session,
                 uint8_t term_type,
                 const long int_term,
                 const char *string_term,
                 const int string_term_len,
                 const char **addresses,
                 const int *docs_per_shard,
                 const packed_table_ptr *shards,
                 int num_shard,
                 int stream_num)
{
    struct tgs_desc desc;
    struct ftgs_outstream *stream;

    start_timer(worker, 3);

    term_init(&desc.term, term_type, int_term, string_term, string_term_len);

    /* find the stream data struct by index */
    stream = &worker->out_streams[stream_num];

    /* init the tsg struct */
    tgs_init(worker,
             &desc,
             term_type,
             addresses,
             docs_per_shard,
             shards,
             num_shard,
             stream,
             session);

    /* do the Term Group Stats accumulation pass */
    int err;
    err = tgs_execute_pass(worker, session, &desc);
    if (err != 0) {
        if (desc.stream->socket.err.code != 0) {
            worker->error = desc.stream->socket.err;
        }
    }

    /* clean up the tgs structure */
    tgs_destroy(&desc);

    term_destroy(term_type, &desc.term);

    end_timer(worker, 3);

    return err;
}


/* No need to share the group stats buffer, so just keep one per session*/
/* Make sure the one we have is large enough */
static unpacked_table_ptr allocate_grp_stats(struct session_desc *session,
                                            const packed_table_ptr metric_desc)
{
	unpacked_table_ptr grp_stats;

	grp_stats = unpacked_table_create(metric_desc, session->num_groups);
	session->temp_buf = unpacked_table_copy_layout(grp_stats, PREFETCH_BUFFER_SIZE);

	return grp_stats;
}

void session_init(struct session_desc *session,
                  const int n_groups,
                  const int n_stats,
                  const int only_binary_metrics,
                  const packed_table_ptr sample_table)
{
    session->num_groups = n_groups;
    session->num_stats = n_stats;
    session->only_binary_metrics = only_binary_metrics;

    session->grp_stats = allocate_grp_stats(session, sample_table);
    session->grp_buf = circular_buffer_int_alloc(CIRC_BUFFER_SIZE);
    session->nz_grps_buf = calloc(n_groups, sizeof(uint32_t));
}

void session_destroy(struct session_desc *session)
{
    unpacked_table_destroy(session->grp_stats);

    /* free the intermediate buffers */
    circular_buffer_int_cleanup(session->grp_buf);
    free(session->nz_grps_buf);
    unpacked_table_destroy(session->temp_buf);
}



int worker_start_field(struct worker_desc *worker,
                       const char *field_name,
                       int len,
                       int term_type,
                       int stream_num)
{
    int err;

    if (stream_num >= worker->num_streams) {
        worker->error.code = EBADF;
        snprintf(worker->error.str, sizeof(worker->error.str),
                 "Invalid socket number. stream_num: %d num_streams: %d",
                 stream_num, worker->num_streams);
        return -1;
    }
    err = write_field_start(&worker->out_streams[stream_num], field_name, len, term_type);
    if (err != 0) {
        worker->error = worker->out_streams[stream_num].socket.err;
        worker->out_streams[stream_num].socket.err.code = 0;
        return err;
    }
    return 0;
}

int worker_end_field(struct worker_desc *worker, int stream_num)
{
    int err;

    if (stream_num >= worker->num_streams) {
        worker->error.code = EBADF;
        snprintf(worker->error.str, sizeof(worker->error.str), "Invalid socket number.");
        return -1;
    }
    err = write_field_end(&worker->out_streams[stream_num]);
    if (err == -1) {
        worker->error = worker->out_streams[stream_num].socket.err;
        worker->out_streams[stream_num].socket.err.code = 0;
        return -1;
    }
    return 0;
}

int worker_end_stream(struct worker_desc *worker, int stream_num)
{
    int err;

    if (stream_num >= worker->num_streams) {
        worker->error.code = EBADF;
        snprintf(worker->error.str, sizeof(worker->error.str), "Invalid socket number.");
        return -1;
    }
    err = write_stream_end(&worker->out_streams[stream_num]);
    if (err == -1) {
        worker->error = worker->out_streams[stream_num].socket.err;
        worker->out_streams[stream_num].socket.err.code = 0;
        return -1;
    }
    return 0;
}

void worker_init(struct worker_desc *worker,
                 const int *socket_fds,
                 int num_sockets)
{
    worker->num_streams = num_sockets;
    worker->out_streams = calloc(num_sockets, sizeof(struct ftgs_outstream));
    for (int i = 0; i < num_sockets; i++) {
        stream_init(&worker->out_streams[i], socket_fds[i]);
    }

//    for (int i = 0; i < 32; i++) {
//        worker->timings[i] = 0;
//    }
}

void worker_destroy(struct worker_desc *worker)
{
//    for (int i = 1; i < 32; i++) {
//        if (worker->timings[i] == 0)
//            continue;
//        fprintf(stderr, "Timing %d:   %ld\n", i, worker->timings[i]);
//    }

    /* free socket and term entries */
    for (int i = 0; i < worker->num_streams; i++) {
        stream_destroy( &worker->out_streams[i]);
    }

    /* free socket array */
    free(worker->out_streams);
}