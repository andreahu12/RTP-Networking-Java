# RTP-Networking-Java
Using UDP Sockets to mimic TCP

Milestones

	) Send a packet from multiple clients to server and back (Thur) - Jeffer

	----
	) Implement flow control and package re-ordering detection - the sequence and ack number thing (Fri) - Jeffer
		ackno = seqno+packload

	) Implement lost packet detection & duplicate - timeouts (sat) - Andrea
		send - timeout and resend
		receive - make ack no. (which will probably be done in flow crt) and check dupes

	) Implement package corruption detection - checksum (sat) - Andrea
		at receieve

	) Implement Congestion control - RTT and slow start and ssthresh stuff (mon) - Jeffer

	) Office hours (tue) - 1:30pm-3pm

	) Write FTA (by tue) - Andrea
		
